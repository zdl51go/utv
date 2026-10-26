package com.bjunicom.iptv;

import com.bjunicom.iptv.player.HevcStartupGate;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HevcStartupGateTest {
    @Test
    public void waitsForTheEndOfAnIdrAccessUnit() {
        HevcStartupGate gate = new HevcStartupGate();

        gate.observeNalStart(32, false); // VPS
        gate.observeNalStart(33, false); // SPS
        gate.observeNalStart(34, false); // PPS
        gate.observeNalStart(19, false); // IDR starts

        assertFalse(gate.isReady());

        gate.observeNalStart(1, false); // next NAL proves the IDR ended

        assertTrue(gate.isReady());
    }

    @Test
    public void randomAccessFlagAloneDoesNotOpenTheGate() {
        HevcStartupGate gate = new HevcStartupGate();

        gate.observeNalStart(19, false);

        assertFalse(gate.isReady());
    }
}
