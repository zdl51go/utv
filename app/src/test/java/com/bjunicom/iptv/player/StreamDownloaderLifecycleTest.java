package com.bjunicom.iptv.player;

import android.app.Application;
import android.net.Uri;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class StreamDownloaderLifecycleTest {
    private AtomicBoolean httpTransportBroken;
    private boolean previousTransportState;

    @Before public void enableLocalHttpTransport() throws Exception {
        Field field = StreamDownloaderPool.class.getDeclaredField("httpTransportBroken");
        field.setAccessible(true);
        httpTransportBroken = (AtomicBoolean) field.get(null);
        previousTransportState = httpTransportBroken.getAndSet(false);
    }

    @After public void restoreTransportState() {
        if (httpTransportBroken != null) httpTransportBroken.set(previousTransportState);
    }

    @Test public void releaseReturnsPromptlyButCannotRecycleUntilHttpWorkerExits() throws Exception {
        StreamDownloaderPool pool = isolatedPool();
        StreamDownloaderPool.PooledStreamDownloader downloader = pool.acquire();
        StreamDownloaderPool.PooledStreamDownloader reserve = pool.acquire();
        Queue<?> idle = idleQueue(pool);
        assertTrue(idle.isEmpty());

        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackMayReturn = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        ExecutorService stopper = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "DownloaderStopTest");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> release = null;
        StreamDownloaderPool.PooledStreamDownloader replacement = null;
        try (HttpTsSource source = new HttpTsSource()) {
            downloader.startDownload(source.uri(), null, new StreamDownloaderPool.OnStreamEventsListener() {
                @Override public void onFirstPacket(StreamDownloaderPool.PooledStreamDownloader ignored) {
                    callbackEntered.countDown();
                    try {
                        if (!callbackMayReturn.await(10, TimeUnit.SECONDS)) {
                            workerFailure.set(new AssertionError("Test never released the first-packet callback"));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        workerFailure.set(e);
                    }
                }
                @Override public void onCodecParsed(TsCodecDescriptor value,
                        StreamDownloaderPool.PooledStreamDownloader ignored) {
                    workerFailure.set(new AssertionError("Stopped worker reported a codec after callback returned"));
                }
                @Override public void onStreamError(String reason,
                        StreamDownloaderPool.PooledStreamDownloader ignored) {
                    workerFailure.set(new AssertionError(reason));
                }
            });
            assertTrue("Real HTTP worker did not reach its first TS packet",
                    callbackEntered.await(5, TimeUnit.SECONDS));
            source.assertResponseSent();

            // Run the UI-equivalent stop independently so a monitor held by the
            // callback cannot deadlock the test thread or hide a blocking stop.
            release = stopper.submit(() -> pool.release(downloader));
            try {
                release.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                throw new AssertionError("release() blocked waiting for an in-flight callback; UI stop must return promptly", timeout);
            }
            assertFalse("In-flight downloader was returned to idlePool before its worker exited",
                    idle.contains(downloader));
            replacement = pool.acquire();
            assertNotSame("Replacement channel borrowed a downloader still owned by the old worker",
                    downloader, replacement);

            callbackMayReturn.countDown();
            awaitRecycled(idle, downloader);
            StreamDownloaderPool.PooledStreamDownloader reused = pool.acquire();
            assertSame("Downloader should become reusable once the old worker finishes", downloader, reused);
            assertNull("Stopped HTTP worker must finish without stale callbacks", workerFailure.get());
            pool.release(reused);
        } finally {
            callbackMayReturn.countDown();
            if (release != null) {
                try { release.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            downloader.stopSession();
            if (replacement != null) pool.release(replacement);
            pool.release(reserve);
            stopper.shutdownNow();
            pool.getDownloadExecutor().shutdownNow();
        }
    }

    @Test public void repeatedReleaseOfIdleDownloaderCannotLeaseTheSameInstanceTwice() throws Exception {
        StreamDownloaderPool pool = isolatedPool();
        StreamDownloaderPool.PooledStreamDownloader downloader = pool.acquire();
        StreamDownloaderPool.PooledStreamDownloader reserve = pool.acquire();
        try {
            pool.release(downloader);
            pool.release(downloader);
            assertEquals("Repeated release must leave only one idle entry", 1, idleQueue(pool).size());
            StreamDownloaderPool.PooledStreamDownloader first = pool.acquire();
            StreamDownloaderPool.PooledStreamDownloader second = pool.acquire();
            assertSame(downloader, first);
            assertNotSame("Two borrowers must never receive the same downloader", first, second);
            pool.release(first);
            pool.release(second);
        } finally {
            pool.release(reserve);
            pool.getDownloadExecutor().shutdownNow();
        }
    }

    private static StreamDownloaderPool isolatedPool() throws Exception {
        Constructor<StreamDownloaderPool> constructor = StreamDownloaderPool.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Queue<?> idleQueue(StreamDownloaderPool pool) throws Exception {
        Field field = StreamDownloaderPool.class.getDeclaredField("idlePool");
        field.setAccessible(true);
        return (Queue<?>) field.get(pool);
    }

    private static void awaitRecycled(Queue<?> idle,
            StreamDownloaderPool.PooledStreamDownloader downloader) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!idle.contains(downloader) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Exited HTTP worker never returned its downloader to the pool", idle.contains(downloader));
    }

    /** One real local HTTP response; playback still runs on the production executor. */
    private static final class HttpTsSource implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "LocalTsHttpTest");
            thread.setDaemon(true);
            return thread;
        });
        private final Future<?> response;

        HttpTsSource() throws Exception {
            response = serverExecutor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader request = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    for (String line; (line = request.readLine()) != null && !line.isEmpty(); ) {}
                    byte[] body = TsFixtures.concat(TsFixtures.pat(), TsFixtures.pmt(false), TsFixtures.h264Idr());
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }

        Uri uri() { return Uri.parse("http://127.0.0.1:" + server.getLocalPort() + "/live.ts"); }
        void assertResponseSent() throws Exception { response.get(2, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            server.close();
            serverExecutor.shutdownNow();
        }
    }
}
