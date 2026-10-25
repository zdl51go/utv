package com.bjunicom.iptv.network;

/** Validates RTP framing before MPEG-TS bytes reach a decoder. */
public final class RtpTsPacketParser {
    public static final class Payload {
        public final int offset;
        public final int length;
        public final boolean discontinuity;

        private Payload(int offset, int length, boolean discontinuity) {
            this.offset = offset;
            this.length = length;
            this.discontinuity = discontinuity;
        }
    }

    private int previousSequence = -1;
    private int previousSsrc;

    public void reset() {
        previousSequence = -1;
        previousSsrc = 0;
    }

    /**
     * Accepts either raw 188-byte aligned TS or RTP carrying aligned TS.
     * Duplicate/late RTP packets are rejected; a sequence gap is reported so
     * callers can invalidate any startup alignment state.
     */
    public Payload parse(byte[] packet, int length) {
        if (packet == null || length <= 0 || length > packet.length) {
            return null;
        }
        int offset = 0;
        int payloadLength = length;
        boolean gap = false;
        int sequence = -1;
        int ssrc = 0;

        if ((packet[0] & 0xff) != 0x47) {
            if (length < 12 || (packet[0] & 0xc0) != 0x80) {
                return null;
            }
            int csrcCount = packet[0] & 0x0f;
            offset = 12 + csrcCount * 4;
            if (offset > length) {
                return null;
            }
            if ((packet[0] & 0x10) != 0) {
                if (offset + 4 > length) {
                    return null;
                }
                int extensionWords = ((packet[offset + 2] & 0xff) << 8)
                        | (packet[offset + 3] & 0xff);
                offset += 4 + extensionWords * 4;
                if (offset > length) {
                    return null;
                }
            }
            int padding = (packet[0] & 0x20) != 0 ? packet[length - 1] & 0xff : 0;
            if (((packet[0] & 0x20) != 0 && padding == 0) || offset + padding >= length) {
                return null;
            }
            payloadLength = length - offset - padding;
            sequence = ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
            ssrc = ((packet[8] & 0xff) << 24) | ((packet[9] & 0xff) << 16)
                    | ((packet[10] & 0xff) << 8) | (packet[11] & 0xff);
            if (previousSequence >= 0 && ssrc != previousSsrc) {
                gap = true;
            } else if (previousSequence >= 0) {
                int delta = (sequence - previousSequence) & 0xffff;
                if (delta == 0 || delta >= 0x8000) {
                    return null;
                }
                gap = delta != 1;
            }
        }

        if (payloadLength <= 0 || payloadLength % 188 != 0) {
            return null;
        }
        for (int p = offset; p < offset + payloadLength; p += 188) {
            if ((packet[p] & 0xff) != 0x47) {
                return null;
            }
        }
        if (sequence >= 0) {
            previousSequence = sequence;
            previousSsrc = ssrc;
        }
        return new Payload(offset, payloadLength, gap);
    }
}
