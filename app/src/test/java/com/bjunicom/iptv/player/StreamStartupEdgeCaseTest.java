package com.bjunicom.iptv.player;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class StreamStartupEdgeCaseTest {
    @Test public void validPmtAcrossTwoTsPacketsCanStartPlayback() throws Exception {
        try (Stream stream = new Stream()) {
            // One valid private programme descriptor makes the complete PMT 186 bytes.
            // The video/audio declarations fit in packet 1; only CRC crosses packet 2.
            byte[] section = new byte[186];
            byte[] header = {2, (byte) 0xb0, (byte) 183, 0, 1, (byte) 0xc1, 0, 0,
                    (byte) 0xe1, 1, (byte) 0xf0, (byte) 160};
            System.arraycopy(header, 0, section, 0, header.length);
            section[12] = (byte) 0x80;
            section[13] = (byte) 158;
            byte[] streams = {0x24, (byte) 0xe1, 1, (byte) 0xf0, 0,
                    3, (byte) 0xe1, 2, (byte) 0xf0, 0};
            System.arraycopy(streams, 0, section, 172, streams.length);
            int crc = -1;
            for (int i = 0; i < section.length - 4; i++) {
                crc ^= (section[i] & 255) << 24;
                for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
            }
            for (int i = 0; i < 4; i++) section[section.length - 4 + i] = (byte) (crc >>> (24 - 8 * i));
            byte[] firstPayload = new byte[184];
            System.arraycopy(section, 0, firstPayload, 1, 183);
            byte[] first = TsFixtures.packet(TsFixtures.PMT_PID, true, firstPayload);
            byte[] second = TsFixtures.packet(TsFixtures.PMT_PID, false,
                    java.util.Arrays.copyOfRange(section, 183, section.length));
            second[3] = 0x11;
            stream.feed(TsFixtures.pat(), first, second, hevcStart(0), hevcEnd(1));
            assertNotNull("CRC must be checked after complete multi-packet PSI reassembly", stream.descriptor);
            byte[] replay = new byte[5 * 188];
            assertEquals(replay.length, stream.downloader.readAt(0, replay, 0, replay.length));
            assertArrayEquals("Decoder must receive every fragment of the validated PMT",
                    TsFixtures.concat(TsFixtures.pat(), first, second, hevcStart(0), hevcEnd(1)), replay);
        }
    }

    @Test public void futureProgrammeMapDoesNotInvalidateCurrentHevcWindow() throws Exception {
        try (Stream stream = new Stream()) {
            byte[] future = TsFixtures.pmt(true);
            future[3] = 0x11;
            future[10] = (byte) 0xc2; // next version, current_next_indicator == 0
            updateCrc(future);
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(0), future, hevcEnd(1));
            assertNotNull("A valid future PMT must not erase the active programme's startup window", stream.descriptor);
        }
    }

    @Test public void unrelatedPidContinuityGapDoesNotInvalidateSelectedHevcWindow() throws Exception {
        try (Stream stream = new Stream()) {
            byte[] firstOther = TsFixtures.packet(500, false, new byte[]{11});
            byte[] gapOther = TsFixtures.packet(500, false, new byte[]{12});
            gapOther[3] = 0x12;
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), firstOther, hevcStart(0), gapOther, hevcEnd(1));
            assertNotNull("Loss on another programme PID does not damage the selected video", stream.descriptor);
        }
    }

    @Test public void oldPacketCannotResetNewSessionAfterFirstPacketCallbackYields() throws Exception {
        try (Stream stream = new Stream()) {
            stream.onFirst = () -> {
                try {
                    // Deterministic interleaving: old worker is inside its callback while
                    // the downloader is returned, reset and used for a replacement channel.
                    stream.downloader.stopSession();
                    stream.downloader.resetForAcquire();
                    stream.activate();
                    stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(0));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            };
            byte[] staleCorrupt = TsFixtures.pat();
            staleCorrupt[1] |= (byte) 0x80;
            stream.feed(staleCorrupt);
            stream.feed(hevcEnd(1));
            assertNotNull("Old worker must not clear new session metadata or HEVC gate", stream.descriptor);
        }
    }

    private static byte[] hevcStart(int cc) {
        byte[] packet = TsFixtures.pes(TsFixtures.VIDEO_PID, 90000, new byte[]{
                0, 0, 1, 64, 1, 0, 0, 1, 66, 1, 0, 0, 1, 68, 1, 0, 0, 1, 38, 1});
        packet[3] = (byte) (0x10 | cc);
        return packet;
    }

    private static byte[] hevcEnd(int cc) {
        byte[] packet = TsFixtures.pes(TsFixtures.VIDEO_PID, 93600, new byte[]{0, 0, 1, 2, 1});
        packet[3] = (byte) (0x10 | cc);
        return packet;
    }

    private static void updateCrc(byte[] packet) {
        int end = 5 + 3 + (((packet[6] & 15) << 8) | (packet[7] & 255));
        int crc = -1;
        for (int i = 5; i < end - 4; i++) {
            crc ^= (packet[i] & 255) << 24;
            for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        for (int i = 0; i < 4; i++) packet[end - 4 + i] = (byte) (crc >>> (24 - 8 * i));
    }

    private static class Stream implements AutoCloseable, StreamDownloaderPool.OnStreamEventsListener {
        final StreamDownloaderPool.PooledStreamDownloader downloader = StreamDownloaderPool.getInstance().acquire();
        final Method accept;
        int token;
        TsCodecDescriptor descriptor;
        Runnable onFirst;

        Stream() throws Exception {
            activate();
            accept = downloader.getClass().getDeclaredMethod("onAlignedTsPacket", int.class,
                    byte[].class, int.class, StreamDownloaderPool.OnStreamEventsListener.class);
            accept.setAccessible(true);
        }

        void activate() throws Exception {
            set("sessionActive", true);
            set("parseStartTimeMs", System.currentTimeMillis());
            Field field = downloader.getClass().getDeclaredField("sessionToken");
            field.setAccessible(true);
            token = ((AtomicInteger) field.get(downloader)).get();
        }

        void feed(byte[]... packets) throws Exception {
            for (byte[] packet : packets) accept.invoke(downloader, token, packet, 0, this);
        }

        void set(String name, Object value) throws Exception {
            Field field = downloader.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(downloader, value);
        }

        @Override public void onFirstPacket(StreamDownloaderPool.PooledStreamDownloader source) {
            Runnable callback = onFirst;
            onFirst = null;
            if (callback != null) callback.run();
        }
        @Override public void onCodecParsed(TsCodecDescriptor value, StreamDownloaderPool.PooledStreamDownloader source) {
            descriptor = value;
        }
        @Override public void onStreamError(String reason, StreamDownloaderPool.PooledStreamDownloader source) {
            fail(reason);
        }
        @Override public void close() { StreamDownloaderPool.getInstance().release(downloader); }
    }
}
