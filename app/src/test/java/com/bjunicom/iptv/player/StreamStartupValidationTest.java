package com.bjunicom.iptv.player;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class StreamStartupValidationTest {
    @Test public void corruptProgrammeMapCannotSelectADecoder() throws Exception {
        try (Stream stream = new Stream()) {
            byte[] corrupt = TsFixtures.pmt(false);
            corrupt[20] ^= 1;
            stream.feed(TsFixtures.pat(), corrupt, TsFixtures.h264Idr());
            assertNull(stream.descriptor);
            byte[] cleanIdr = TsFixtures.h264Idr();
            cleanIdr[3] = 0x11;
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(false), cleanIdr);
            assertNotNull(stream.descriptor);
            assertEquals("h264", stream.descriptor.videoCodec);
        }
    }

    @Test public void continuityGapCannotCompleteAnEarlierHevcIdr() throws Exception {
        try (Stream stream = new Stream()) {
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(0));
            assertNull(stream.descriptor);
            stream.feed(hevcEnd(2)); // A TS packet was lost, even without an RTP sequence header.
            assertNull(stream.descriptor);
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(3), hevcEnd(4));
            assertNotNull(stream.descriptor);
            assertTrue(stream.descriptor.isHevc());
        }
    }

    @Test public void explicitDiscontinuityInAdaptationOnlyPacketInvalidatesStartup() throws Exception {
        try (Stream stream = new Stream()) {
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(0));
            byte[] discontinuity = TsFixtures.packet(TsFixtures.VIDEO_PID, false, new byte[]{1});
            discontinuity[3] = 0x20;
            discontinuity[4] = (byte) 183;
            discontinuity[5] = (byte) 0x80;
            stream.feed(discontinuity, hevcEnd(1));
            assertNull(stream.descriptor);
        }
    }

    @Test public void duplicateAndNullPacketsDoNotBreakAValidStartup() throws Exception {
        try (Stream stream = new Stream()) {
            byte[] start = hevcStart(15);
            byte[] nullPacket = TsFixtures.packet(8191, false, new byte[]{1});
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), start, start,
                    nullPacket, nullPacket, hevcEnd(0)); // CC wraps at 15, not a gap.
            assertNotNull(stream.descriptor);
        }
    }

    @Test public void partialPsiSectionCannotBeAcceptedAsAProgrammeMap() throws Exception {
        try (Stream stream = new Stream()) {
            byte[] partial = TsFixtures.pmt(false);
            partial[7] = (byte) 250; // section length extends beyond this TS packet.
            stream.feed(TsFixtures.pat(), partial, TsFixtures.h264Idr());
            assertNull(stream.descriptor);
        }
    }

    @Test public void programmeMapVersionChangeInvalidatesOldHevcParameters() throws Exception {
        try (Stream stream = new Stream()) {
            byte[] updated = TsFixtures.pmt(true);
            updated[3] = 0x11;
            updated[10] = (byte) 0xc3;
            updateCrc(updated);
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(0), updated, hevcEnd(1));
            assertNull(stream.descriptor);
            stream.feed(hevcStart(2), hevcEnd(3));
            assertNotNull(stream.descriptor);
        }
    }

    @Test public void differentPacketsWithTheSameCcInvalidatePartialIdr() throws Exception {
        try (Stream stream = new Stream()) {
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(0), hevcEnd(0));
            assertNull(stream.descriptor);
        }
    }

    @Test public void alignmentDeadlineStartsAtFirstPacketAndReportsFormatOnlyOnce() throws Exception {
        try (Stream stream = new Stream()) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(60)); // Joining a group has no TS deadline yet.
            stream.feed(TsFixtures.pat(), TsFixtures.pmt(true), hevcStart(0));
            assertNull(stream.failure);
            ShadowSystemClock.advanceBy(Duration.ofMillis(15001));
            stream.feed(hevcEnd(1), hevcEnd(2));
            assertNull(stream.descriptor);
            assertEquals(PlaybackFailure.FORMAT, stream.failure);
            assertEquals(1, stream.failures);
        }
    }

    private static byte[] hevcStart(int cc) {
        byte[] packet = TsFixtures.pes(TsFixtures.VIDEO_PID, 90000, new byte[]{
                0, 0, 1, 64, 1, 0, 0, 1, 66, 1, 0, 0, 1, 68, 1, 0, 0, 1, 38, 1});
        packet[3] = (byte) (0x10 | cc);
        return packet;
    }

    private static byte[] hevcEnd(int cc) {
        byte[] packet = TsFixtures.pes(TsFixtures.VIDEO_PID, 93600,
                new byte[]{0, 0, 1, 2, 1});
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
        final int token;
        TsCodecDescriptor descriptor;
        PlaybackFailure failure;
        int failures;

        Stream() throws Exception {
            set("sessionActive", true);
            set("parseStartTimeMs", System.currentTimeMillis());
            Field field = downloader.getClass().getDeclaredField("sessionToken");
            field.setAccessible(true);
            token = ((AtomicInteger) field.get(downloader)).get();
            accept = downloader.getClass().getDeclaredMethod("onAlignedTsPacket", int.class,
                    byte[].class, int.class, StreamDownloaderPool.OnStreamEventsListener.class);
            accept.setAccessible(true);
        }

        void feed(byte[]... packets) throws Exception {
            for (byte[] packet : packets) accept.invoke(downloader, token, packet, 0, this);
        }

        void set(String name, Object value) throws Exception {
            Field field = downloader.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(downloader, value);
        }

        @Override public void onCodecParsed(TsCodecDescriptor value, StreamDownloaderPool.PooledStreamDownloader source) {
            descriptor = value;
        }
        @Override public void onStreamError(String reason, StreamDownloaderPool.PooledStreamDownloader source) {
            fail(reason);
        }
        @Override public void onStreamError(PlaybackFailure value, String reason,
                                            StreamDownloaderPool.PooledStreamDownloader source) {
            failure = value;
            failures++;
        }
        @Override public void close() { StreamDownloaderPool.getInstance().release(downloader); }
    }
}
