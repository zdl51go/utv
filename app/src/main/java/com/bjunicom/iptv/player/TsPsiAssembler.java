package com.bjunicom.iptv.player;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded PAT/PMT section assembly. CRC is checked only after all fragments arrive. */
final class TsPsiAssembler {
    static final class Section {
        final byte[] data;
        final byte[] packets;

        Section(byte[] data, byte[] packets) {
            this.data = data;
            this.packets = packets;
        }
    }

    private final int tableId;
    private final byte[] section = new byte[1024]; // ISO/IEC 13818-1: section_length <= 1021.
    private final ByteArrayOutputStream packets = new ByteArrayOutputStream();
    private int size;
    private int expected;
    private long callId;
    private long capturedCall = -1;
    private boolean corrupt;

    TsPsiAssembler(int tableId) { this.tableId = tableId; }

    void reset() {
        size = expected = 0;
        packets.reset();
        capturedCall = -1;
        corrupt = false;
    }

    boolean wasCorrupt() { return corrupt; }

    List<Section> accept(byte[] packet, int offset, int start, int end, boolean pusi) {
        corrupt = false;
        callId++;
        List<Section> completed = new ArrayList<>();
        if (pusi) {
            int next = start + 1 + (packet[start] & 255);
            if (next > end) {
                discardCorrupt();
                return completed;
            }
            if (size > 0) {
                append(packet, offset, start + 1, next, false, completed);
                if (size > 0) discardCorrupt();
            }
            // A PUSI establishes a fresh section boundary, even after a truncated section.
            append(packet, offset, next, end, true, completed);
        } else if (size > 0) {
            append(packet, offset, start, end, false, completed);
        }
        return completed;
    }

    private void append(byte[] packet, int offset, int start, int end, boolean newSections,
                        List<Section> completed) {
        int pos = start;
        while (pos < end) {
            if (size == 0 && (!newSections || (packet[pos] & 255) == 255)) return;
            if (capturedCall != callId) {
                // Even a legal stream with one payload byte per packet remains bounded.
                if (packets.size() >= 1024 * 188) {
                    discardCorrupt();
                    return;
                }
                packets.write(packet, offset, 188);
                capturedCall = callId;
            }
            int count = Math.min(end - pos, (expected == 0 ? 3 : expected) - size);
            System.arraycopy(packet, pos, section, size, count);
            size += count;
            pos += count;
            if (size == 3 && expected == 0) {
                int length = ((section[1] & 15) << 8) | (section[2] & 255);
                if ((section[0] & 255) != tableId || (section[1] & 0x80) == 0
                        || length < (tableId == 2 ? 13 : 9) || length > 1021) {
                    discardCorrupt();
                    return;
                }
                expected = length + 3;
            }
            if (expected > 0 && size == expected) {
                if (validCrc(section, size)) {
                    // Future tables are valid but must not replace the active programme map.
                    if ((section[5] & 1) != 0) {
                        completed.add(new Section(Arrays.copyOf(section, size), packets.toByteArray()));
                    }
                } else {
                    corrupt = true;
                }
                clearPartial();
                if (!newSections) return;
            }
        }
    }

    private void discardCorrupt() {
        corrupt = true;
        clearPartial();
    }

    private void clearPartial() {
        size = expected = 0;
        packets.reset();
        capturedCall = -1;
    }

    private static boolean validCrc(byte[] data, int length) {
        int crc = -1;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 255) << 24;
            for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        return crc == 0;
    }
}
