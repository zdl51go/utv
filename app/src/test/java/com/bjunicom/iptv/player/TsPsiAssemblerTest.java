package com.bjunicom.iptv.player;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TsPsiAssemblerTest {
    @Test public void assemblesEvenWhenSectionHeaderAndCrcAreFragmented() {
        TsPsiAssembler assembler = new TsPsiAssembler(2);
        byte[] data = section(TsFixtures.pmt(true));
        byte[] first = fragment(true, new byte[]{0, data[0]});
        byte[] middle = fragment(false, Arrays.copyOfRange(data, 1, data.length - 2));
        byte[] last = fragment(false, Arrays.copyOfRange(data, data.length - 2, data.length));
        assertTrue(accept(assembler, first).isEmpty());
        assertTrue(accept(assembler, middle).isEmpty());
        List<TsPsiAssembler.Section> result = accept(assembler, last);
        assertEquals(1, result.size());
        assertArrayEquals(data, result.get(0).data);
        assertArrayEquals(TsFixtures.concat(first, middle, last), result.get(0).packets);
    }

    @Test public void pointerCanFinishPreviousSectionAndStartAnotherInTheSamePacket() {
        TsPsiAssembler assembler = new TsPsiAssembler(2);
        byte[] data = section(TsFixtures.pmt(true));
        byte[] prefix = new byte[9];
        System.arraycopy(data, 0, prefix, 1, 8);
        byte[] first = fragment(true, prefix);
        assertTrue(accept(assembler, first).isEmpty());
        int remaining = data.length - 8;
        byte[] payload = new byte[1 + remaining + data.length];
        payload[0] = (byte) remaining;
        System.arraycopy(data, 8, payload, 1, remaining);
        System.arraycopy(data, 0, payload, 1 + remaining, data.length);
        byte[] second = fragment(true, payload);
        List<TsPsiAssembler.Section> result = accept(assembler, second);
        assertFalse(assembler.wasCorrupt());
        assertEquals(2, result.size());
        assertArrayEquals(data, result.get(0).data);
        assertArrayEquals(data, result.get(1).data);
        assertArrayEquals(TsFixtures.concat(first, second), result.get(0).packets);
        assertArrayEquals(second, result.get(1).packets);
    }

    @Test public void corruptCrcAndOversizedSectionsAreRejectedAndDoNotPoisonNextTable() {
        TsPsiAssembler assembler = new TsPsiAssembler(2);
        byte[] bad = TsFixtures.pmt(true);
        bad[20] ^= 1;
        assertTrue(accept(assembler, bad).isEmpty());
        assertTrue(assembler.wasCorrupt());
        assertTrue(accept(assembler, fragment(true, new byte[]{0, 2, (byte) 0xb4, 0})).isEmpty());
        assertTrue(assembler.wasCorrupt());
        assertEquals(1, accept(assembler, TsFixtures.pmt(true)).size());
        assertFalse(assembler.wasCorrupt());
    }

    @Test public void orphanContinuationAndInvalidPointerCannotCreateAProgrammeMap() {
        TsPsiAssembler assembler = new TsPsiAssembler(2);
        assertTrue(accept(assembler, fragment(false, section(TsFixtures.pmt(true)))).isEmpty());
        assertTrue(accept(assembler, fragment(true, new byte[]{100, 2})).isEmpty());
        assertTrue(assembler.wasCorrupt());
        assertEquals(1, accept(assembler, TsFixtures.pmt(true)).size());
    }

    private static List<TsPsiAssembler.Section> accept(TsPsiAssembler assembler, byte[] packet) {
        int start = (packet[3] & 0x20) == 0 ? 4 : 5 + (packet[4] & 255);
        return assembler.accept(packet, 0, start, 188, (packet[1] & 0x40) != 0);
    }

    private static byte[] section(byte[] packet) {
        int length = 3 + (((packet[6] & 15) << 8) | (packet[7] & 255));
        return Arrays.copyOfRange(packet, 5, 5 + length);
    }

    private static byte[] fragment(boolean pusi, byte[] payload) {
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 255);
        packet[0] = 0x47;
        packet[1] = (byte) ((pusi ? 0x40 : 0) | 1);
        packet[2] = 0;
        packet[3] = 0x30;
        packet[4] = (byte) (183 - payload.length);
        packet[5] = 0;
        System.arraycopy(payload, 0, packet, 188 - payload.length, payload.length);
        return packet;
    }
}
