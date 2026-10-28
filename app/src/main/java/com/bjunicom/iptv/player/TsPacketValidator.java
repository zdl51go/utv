package com.bjunicom.iptv.player;

import java.util.Arrays;

/** Validates TS framing and per-PID continuity, including streams without RTP headers. */
final class TsPacketValidator {
    enum Result { ACCEPT, DISCONTINUITY, DUPLICATE, CORRUPT }

    private final int[] continuity = new int[8192];
    private final int[] packetHashes = new int[8192];

    TsPacketValidator() { reset(); }

    void reset() { Arrays.fill(continuity, -1); }

    Result inspect(byte[] packet, int offset) {
        if (offset < 0 || offset + 188 > packet.length || (packet[offset] & 255) != 0x47
                || (packet[offset + 1] & 0x80) != 0 || (packet[offset + 3] & 0xc0) != 0) {
            return Result.CORRUPT;
        }
        int control = (packet[offset + 3] >> 4) & 3;
        if (control == 0) return Result.CORRUPT;
        boolean discontinuity = false;
        if ((control & 2) != 0) {
            int length = packet[offset + 4] & 255;
            if (length > (control == 3 ? 182 : 183)) return Result.CORRUPT;
            discontinuity = length > 0 && (packet[offset + 5] & 0x80) != 0;
        }
        int pid = ((packet[offset + 1] & 31) << 8) | (packet[offset + 2] & 255);
        if (pid == 8191) return Result.ACCEPT; // Null packet CC has no programme timing meaning.
        if (discontinuity) continuity[pid] = -1;
        if ((control & 1) != 0) {
            int cc = packet[offset + 3] & 15;
            int hash = 1;
            for (int i = offset; i < offset + 188; i++) hash = 31 * hash + packet[i];
            if (continuity[pid] == cc && packetHashes[pid] == hash) return Result.DUPLICATE;
            if (continuity[pid] >= 0 && cc != ((continuity[pid] + 1) & 15)) discontinuity = true;
            continuity[pid] = cc;
            packetHashes[pid] = hash;
        }
        return discontinuity ? Result.DISCONTINUITY : Result.ACCEPT;
    }

}
