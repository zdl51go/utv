package com.bjunicom.iptv.player;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSurfaceView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class,
        shadows = IjkPreparationEdgeCaseTest.ShadowIjk.class)
public class IjkPreparationEdgeCaseTest {
    @Test public void pausedExpandedProbeDoesNotResumeWhenHevcRequiresFreshDecoder() throws Exception {
        TestView view = new TestView(RuntimeEnvironment.getApplication());
        set(view, "mUri", Uri.parse("rtp://239.1.1.1:8008"));
        view.setOnHevcSoftwareDecodeListener(() -> {});
        view.meta = IjkPreparationTest.metadata("hevc", 0, 0, -99);
        IjkMediaPlayer first = attachPooledHevc(view);
        view.start();
        prepared(first);
        assertEquals(true, get(view, "mExpandedIjkProbeWindow"));
        view.pause();
        view.meta = IjkPreparationTest.metadata("hevc", 3840, 2160, -99);
        IjkMediaPlayer second = attachPooledHevc(view);
        prepared(second);
        assertEquals(1, get(view, "mHevcFreshRetryCount"));
        assertEquals("HEVC recovery must preserve pause after metadata retry", 4,
                get(view, "mTargetState"));
        view.stopPlayback();
    }

    @Test public void stopDuringProbeRecoveryCancelsPendingSurfaceOpen() throws Exception {
        TestView view = new TestView(RuntimeEnvironment.getApplication());
        set(view, "mUri", Uri.parse("rtp://239.1.1.1:8008"));
        view.meta = IjkPreparationTest.metadata("hevc", 0, 0, -99);
        IjkMediaPlayer first = attachPooledHevc(view);
        prepared(first);
        view.stopPlayback();
        SurfaceView surface = (SurfaceView) view.getChildAt(0);
        ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(surface).getFakeSurfaceHolder();
        for (SurfaceHolder.Callback callback : holder.getCallbacks()) {
            callback.surfaceCreated(holder);
        }
        Object downloader = get(view, "mActiveDownloader");
        int state = (Integer) get(view, "mCurrentState");
        view.stopPlayback();
        assertNull("A late recovery Surface must not reacquire a downloader after stop", downloader);
        assertEquals(0, state);
    }

    private static void prepared(IjkMediaPlayer player) throws Exception {
        Method notify = tv.danmaku.ijk.media.player.AbstractMediaPlayer.class
                .getDeclaredMethod("notifyOnPrepared");
        notify.setAccessible(true);
        notify.invoke(player);
    }

    private static IjkMediaPlayer attachPooledHevc(TestView view) throws Exception {
        IjkMediaPlayer player = new IjkMediaPlayer();
        TsCodecDescriptor descriptor = TsCodecDescriptor.createDefault(true);
        com.bjunicom.iptv.config.IptvSettings settings =
                (com.bjunicom.iptv.config.IptvSettings) get(view, "mSettings");
        set(view, "mMediaPlayer", player);
        set(view, "mPooledIjkDecoder", new CodecDecoderPool.PooledIjkDecoder(
                player, descriptor.toDecoderPoolKey(settings), descriptor, false));
        set(view, "mActiveCodecDescriptor", descriptor);
        set(view, "mCurrentState", 1);
        Method bind = IjkVideoView.class.getDeclaredMethod("attachPlayerListeners", IMediaPlayer.class);
        bind.setAccessible(true);
        bind.invoke(view, player);
        return player;
    }

    private static Object get(IjkVideoView view, String name) throws Exception {
        Field field = IjkVideoView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(view);
    }

    private static void set(IjkVideoView view, String name, Object value) throws Exception {
        Field field = IjkVideoView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }

    private static class TestView extends IjkVideoView {
        Bundle meta;
        TestView(Context context) { super(context); }
        @Override protected Bundle readIjkMediaMeta(IMediaPlayer player) { return meta; }
    }

    /** Keep real listener dispatch and pool lifecycle, replace only unavailable native operations. */
    @Implements(value = IjkMediaPlayer.class, isInAndroidSdk = false)
    public static class ShadowIjk {
        @Implementation protected void __constructor__() {}
        @Implementation protected void __constructor__(tv.danmaku.ijk.media.player.IjkLibLoader loader) {}
        @Implementation protected static void native_setLogLevel(int level) {}
        @Implementation protected void setOption(int category, String key, long value) {}
        @Implementation protected void setOption(int category, String key, String value) {}
        @Implementation protected void setVolume(float left, float right) {}
        @Implementation protected void setDisplay(SurfaceHolder holder) {}
        @Implementation protected void setSurface(Surface surface) {}
        @Implementation protected void release() {}
        @Implementation protected void reset() {}
        @Implementation protected int getVideoDecoder() { return IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC; }
        @Implementation protected MediaInfo getMediaInfo() {
            MediaInfo info = new MediaInfo();
            info.mAudioDecoderImpl = "avcodec";
            return info;
        }
    }
}
