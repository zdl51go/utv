package com.bjunicom.iptv.player;

import android.os.SystemClock;
import android.util.Log;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/** Monotonic startup timing for one channel request. */
public final class PlaybackMetrics {
    private static final String TAG = "PlaybackMetrics";
    private final long startedAtMs = SystemClock.elapsedRealtime();
    private long preparedMs = -1;
    private long firstVideoMs = -1;
    private long firstPacketMs = -1;

    public void prepared() {
        if (preparedMs < 0) {
            preparedMs = elapsed();
        }
    }

    public void onInfo(int what) {
        if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && firstVideoMs < 0) {
            firstVideoMs = elapsed();
            Log.i(TAG, summary());
        }
    }

    public long getFirstVideoMs() {
        return firstVideoMs;
    }

    public String summary() {
        return "startup firstPacketMs=" + firstPacketMs
                + " preparedMs=" + preparedMs
                + " firstVideoMs=" + firstVideoMs;
    }

    private long elapsed() {
        return SystemClock.elapsedRealtime() - startedAtMs;
    }
}
