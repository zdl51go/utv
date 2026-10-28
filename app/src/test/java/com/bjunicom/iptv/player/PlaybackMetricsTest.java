package com.bjunicom.iptv.player;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PlaybackMetricsTest {
    @Test public void recordsRealStagesOnceAndNeverInventsMissingEvents() {
        PlaybackMetrics metrics = new PlaybackMetrics();
        assertEquals(-1, metrics.getFirstVideoMs());
        assertTrue(metrics.summary().contains("firstPacketMs=-1"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(10));
        metrics.onInfo(PlaybackMetrics.INFO_FIRST_PACKET);
        ShadowSystemClock.advanceBy(Duration.ofMillis(10));
        metrics.onInfo(PlaybackMetrics.INFO_STREAM_INFO);
        metrics.onInfo(IMediaPlayer.MEDIA_INFO_COMPONENT_OPEN);
        metrics.prepared();
        assertEquals(-1, metrics.getFirstVideoMs());
        ShadowSystemClock.advanceBy(Duration.ofMillis(30));
        metrics.onInfo(IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START);
        assertEquals(50, metrics.getFirstVideoMs());
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));
        metrics.onInfo(PlaybackMetrics.INFO_FIRST_PACKET);
        metrics.onInfo(IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START);
        assertEquals(50, metrics.getFirstVideoMs());
        assertTrue(metrics.summary().contains("firstPacketMs=10"));
        assertTrue(metrics.summary().contains("streamInfoMs=20"));
        assertTrue(metrics.summary().contains("firstAudioMs=-1"));
    }
}
