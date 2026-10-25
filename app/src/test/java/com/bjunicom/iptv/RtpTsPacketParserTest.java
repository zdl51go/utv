package com.bjunicom.iptv;

import com.bjunicom.iptv.network.RtpTsPacketParser;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RtpTsPacketParserTest {
    private static byte[] rtp(int sequence, int tsPackets) {
        byte[] packet = new byte[12 + tsPackets * 188];
        packet[0] = (byte) 0x80;
        packet[1] = 33;
        packet[2] = (byte) (sequence >>> 8);
        packet[3] = (byte) sequence;
        for (int i = 0; i < tsPackets; i++) {
            packet[12 + i * 188] = 0x47;
        }
        return packet;
    }

    @Test
    public void validatesSequenceAndReportsLoss() {
        RtpTsPacketParser parser = new RtpTsPacketParser();
        assertFalse(parser.parse(rtp(10, 1), 200).discontinuity);
        assertTrue(parser.parse(rtp(12, 1), 200).discontinuity);
        assertNull(parser.parse(rtp(11, 1), 200));
    }

    @Test
    public void acceptsRawTsAndRejectsUnalignedPayload() {
        RtpTsPacketParser parser = new RtpTsPacketParser();
        byte[] raw = new byte[188 * 2];
        raw[0] = 0x47;
        raw[188] = 0x47;
        assertTrue(parser.parse(raw, raw.length) != null);
        assertNull(parser.parse(new byte[187], 187));
    }
}
