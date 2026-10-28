package com.bjunicom.iptv.player;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.ui.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkLibLoader;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, shadows = HevcFallbackPauseTest.ShadowIjk.class)
public class HevcFallbackPauseTest {
    @Test public void exhaustedHevcRetriesPreservePauseThroughActualActivityFallback() throws Exception {
        assertFallbackPreservesPause(false);
    }

    @Test public void pausingDuringFallbackDelayIsRespectedByActualActivityCallback() throws Exception {
        assertFallbackPreservesPause(true);
    }

    private static void assertFallbackPreservesPause(boolean pauseDuringDelay) throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().get();
            IjkVideoView video = activity.findViewById(R.id.ijk_video_view);
            // Attach the actual activity/view so postDelayed runs, while keeping
            // initial activity setup from starting an unrelated network request.
            video.stopPlayback();
            set(video, IjkVideoView.class, "mUri", null);
            controller.start().resume().visible();
            IptvChannel channel = (IptvChannel) get(activity, MainActivity.class, "currentChannel");
            channel.setResolutionTag("4K");
            channel.setActualResolutionFlag("4K");
            channel.setRawUrl("rtp://239.1.1.1:8008");
            set(video, IjkVideoView.class, "mUri", Uri.parse(channel.getRawUrl()));
            video.setRawChannelUrl(channel.getRawUrl());
            set(video, IjkVideoView.class, "mHevcFreshRetryCount", 2);
            set(video, IjkVideoView.class, "mExpandedIjkProbeWindow", true);

            IjkMediaPlayer player = new IjkMediaPlayer();
            TsCodecDescriptor descriptor = TsCodecDescriptor.createDefault(true);
            IptvSettings settings = (IptvSettings) get(video, IjkVideoView.class, "mSettings");
            set(video, IjkVideoView.class, "mMediaPlayer", player);
            set(video, IjkVideoView.class, "mPooledIjkDecoder", new CodecDecoderPool.PooledIjkDecoder(
                    player, descriptor.toDecoderPoolKey(settings), descriptor, false));
            set(video, IjkVideoView.class, "mActiveCodecDescriptor", descriptor);
            set(video, IjkVideoView.class, "mCurrentState", 1);
            Method attach = IjkVideoView.class.getDeclaredMethod("attachPlayerListeners", IMediaPlayer.class);
            attach.setAccessible(true);
            attach.invoke(video, player);
            video.start();
            if (!pauseDuringDelay) video.pause();

            Method prepared = AbstractMediaPlayer.class.getDeclaredMethod("notifyOnPrepared");
            prepared.setAccessible(true);
            prepared.invoke(player);
            if (pauseDuringDelay) video.pause();
            assertEquals(4, get(video, IjkVideoView.class, "mTargetState"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1800));

            assertEquals("The real MainActivity fallback must select the system player", true,
                    get(video, IjkVideoView.class, "mForceFallbackPlayer"));
            assertEquals("playChannel() in the fallback callback must preserve the latest pause intent", 4,
                    get(video, IjkVideoView.class, "mTargetState"));
            video.stopPlayback();
        }
    }

    private static Object get(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Implements(value = IjkMediaPlayer.class, isInAndroidSdk = false)
    public static class ShadowIjk {
        @Implementation protected void __constructor__() {}
        @Implementation protected void __constructor__(IjkLibLoader loader) {}
        @Implementation protected static void native_setLogLevel(int level) {}
        @Implementation protected void setOption(int category, String key, long value) {}
        @Implementation protected void setOption(int category, String key, String value) {}
        @Implementation protected void setVolume(float left, float right) {}
        @Implementation protected void setDisplay(SurfaceHolder holder) {}
        @Implementation protected void setSurface(Surface surface) {}
        @Implementation protected void release() {}
        @Implementation protected void reset() {}
        @Implementation protected int getVideoDecoder() { return IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC; }
        @Implementation protected Bundle getMediaMeta() {
            return IjkPreparationTest.metadata("hevc", 3840, 2160, -99);
        }
        @Implementation protected MediaInfo getMediaInfo() {
            MediaInfo info = new MediaInfo();
            info.mAudioDecoderImpl = "avcodec";
            return info;
        }
    }
}
