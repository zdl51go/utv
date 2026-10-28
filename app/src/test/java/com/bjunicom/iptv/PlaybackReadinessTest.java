package com.bjunicom.iptv;

import android.app.Application;
import android.view.View;

import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.ui.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Duration;

import org.robolectric.shadows.ShadowSystemClock;

import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PlaybackReadinessTest {
    @Test public void preparedAndBufferingEndDoNotClaimVideoIsConnected() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().get();
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            IptvChannel channel = (IptvChannel) field(activity, MainActivity.class, "currentChannel");
            channel.setConnected(null);
            IMediaPlayer.OnPreparedListener prepared = (IMediaPlayer.OnPreparedListener)
                    field(video, IjkVideoView.class, "mOnPreparedListener");
            IMediaPlayer.OnInfoListener info = (IMediaPlayer.OnInfoListener)
                    field(video, IjkVideoView.class, "mOnInfoListener");
            prepared.onPrepared(null);
            info.onInfo(null, IMediaPlayer.MEDIA_INFO_BUFFERING_END, 0);
            assertNull(channel.getConnected());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.loading_overlay).getVisibility());
        }
    }

    private static Object field(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test public void firstVideoFrameMarksConnectedAndStoresActualStartupTime() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().get();
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            IptvChannel channel = (IptvChannel) field(activity, MainActivity.class, "currentChannel");
            IMediaPlayer.OnInfoListener info = (IMediaPlayer.OnInfoListener)
                    field(video, IjkVideoView.class, "mInfoListener");
            ShadowSystemClock.advanceBy(Duration.ofMillis(300));
            info.onInfo(null, IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
            assertEquals(Boolean.TRUE, channel.getConnected());
            assertEquals(video.getFirstFrameCostMs(), channel.getLatencyMs());
            assertTrue(channel.getLatencyMs() >= 300);
            assertEquals(View.GONE, activity.findViewById(R.id.loading_overlay).getVisibility());
            ShadowSystemClock.advanceBy(Duration.ofMillis(200));
            info.onInfo(null, IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
            assertEquals(video.getFirstFrameCostMs(), channel.getLatencyMs());
        }
    }
}
