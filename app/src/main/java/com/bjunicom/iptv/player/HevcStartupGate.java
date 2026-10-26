package com.bjunicom.iptv.player;

/**
 * Tracks whether an HEVC startup window contains parameter sets and a complete
 * random access video unit. A transport random-access flag alone is not enough:
 * the packet can still be the middle of a damaged IDR after multicast loss.
 */
public final class HevcStartupGate {
    private int currentNalType = -1;
    private boolean vpsSeen;
    private boolean spsSeen;
    private boolean ppsSeen;
    private boolean idrCompleted;

    public void reset() {
        currentNalType = -1;
        vpsSeen = false;
        spsSeen = false;
        ppsSeen = false;
        idrCompleted = false;
    }

    /**
     * Records a NAL start. Seeing another NAL closes a previously started IDR
     * access unit, which is the first point at which it is safe to expose the
     * buffered startup window to FFmpeg.
     */
    public void observeNalStart(int nalType, boolean accessUnitBoundary) {
        if (isIdrNal(currentNalType)
                && (accessUnitBoundary || nalType != currentNalType)) {
            idrCompleted = true;
        }
        if (nalType == 32) {
            vpsSeen = true;
        } else if (nalType == 33) {
            spsSeen = true;
        } else if (nalType == 34) {
            ppsSeen = true;
        }
        currentNalType = nalType;
    }

    public boolean isReady() {
        return vpsSeen && spsSeen && ppsSeen && idrCompleted;
    }

    public boolean hasParameterSet(int nalType) {
        return (nalType == 32 && vpsSeen)
                || (nalType == 33 && spsSeen)
                || (nalType == 34 && ppsSeen);
    }

    private static boolean isIdrNal(int nalType) {
        return nalType == 19 || nalType == 20 || nalType == 21;
    }
}
