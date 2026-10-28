package com.bjunicom.iptv.player;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class IjkPreparationTest {
    @Test public void audioMetadataCannotHideTheVideoDeclaredByTheProgrammeMap() throws Exception {
        TestView view = view("rtp://239.1.1.1:8008");
        view.meta = IjkStartupPolicyTest.audioOnlyMetadata();
        set(view, "mActiveCodecDescriptor", new TsCodecDescriptor("h264", "mp2", 256, 257, 258,
                0x1b, 3, true, 90000, 0));
        RecordingPlayer player = attach(view);
        int[] prepared = {0};
        view.setOnPreparedListener(mp -> prepared[0]++);
        player.prepared.onPrepared(player.proxy);
        assertEquals(0, prepared[0]);
        assertTrue(player.released);
        assertEquals(true, field(view, "mExpandedIjkProbeWindow"));
        view.release(true);
    }

    @Test public void incompleteMulticastVideoNeverReportsPreparedOrStarts() throws Exception {
        TestView view = view("rtp://239.1.1.1:8008");
        view.meta = metadata("hevc", 0, 0, -99);
        RecordingPlayer player = attach(view);
        int[] prepared = {0};
        view.setOnPreparedListener(mp -> prepared[0]++);
        view.start();
        player.prepared.onPrepared(player.proxy);
        assertEquals(0, prepared[0]);
        assertEquals(0, player.starts);
        assertTrue(player.released);
        assertEquals(0, field(view, "mHevcFreshRetryCount"));
    }

    @Test public void secondIncompleteProbeFailsOnceAsFormatAndReleasesPlayer() throws Exception {
        TestView view = view("http://router/rtp/239.1.1.1:8008");
        view.meta = metadata("h264", 1920, 1080, -99);
        RecordingPlayer first = attach(view);
        first.prepared.onPrepared(first.proxy);
        int[] errors = {0};
        view.setOnErrorListener((mp, what, extra) -> { errors[0]++; return true; });
        RecordingPlayer second = attach(view);
        second.prepared.onPrepared(second.proxy);
        assertEquals(1, errors[0]);
        assertEquals(PlaybackFailure.FORMAT, view.getLastFailure());
        assertTrue(second.released);
        assertEquals(0, second.starts);
    }

    @Test public void oldPreparedAndInfoCallbacksCannotAffectReplacement() throws Exception {
        TestView view = view("rtp://239.1.1.1:8008");
        RecordingPlayer old = attach(view);
        IMediaPlayer.OnPreparedListener stalePrepared = old.prepared;
        IMediaPlayer.OnInfoListener staleInfo = old.info;
        view.release(false);
        RecordingPlayer replacement = attach(view);
        int[] callbacks = {0};
        view.setOnPreparedListener(mp -> callbacks[0]++);
        view.setOnInfoListener((mp, what, extra) -> { callbacks[0]++; return true; });
        stalePrepared.onPrepared(old.proxy);
        staleInfo.onInfo(old.proxy, IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertEquals(0, callbacks[0]);
        assertEquals(0, replacement.starts);
    }

    @Test public void validVideoAndOrdinaryHttpKeepNormalPreparation() throws Exception {
        for (String url : new String[]{"rtp://239.1.1.1:8008", "https://server/live.ts"}) {
            TestView view = view(url);
            view.meta = metadata("hevc", 3840, 2160, -99);
            RecordingPlayer player = attach(view);
            int[] prepared = {0};
            view.setOnPreparedListener(mp -> prepared[0]++);
            player.prepared.onPrepared(player.proxy);
            assertEquals(1, prepared[0]);
            assertFalse(player.released);
            view.release(true);
        }
    }

    @Test public void pausingDuringRecoveryPreventsStartingTheValidatedPlayer() throws Exception {
        TestView view = view("rtp://239.1.1.1:8008");
        view.meta = metadata("h264", 0, 0, -99);
        RecordingPlayer first = attach(view);
        view.start();
        first.prepared.onPrepared(first.proxy);
        view.pause();
        view.meta = metadata("h264", 1920, 1080, 100);
        RecordingPlayer second = attach(view);
        second.prepared.onPrepared(second.proxy);
        assertEquals(0, second.starts);
        view.release(true);
    }

    @Test public void switchingChannelsResetsExpandedProbeBudget() throws Exception {
        TestView view = view("rtp://239.1.1.1:8008");
        view.meta = metadata("hevc", 0, 0, -99);
        RecordingPlayer first = attach(view);
        first.prepared.onPrepared(first.proxy);
        assertEquals(true, field(view, "mExpandedIjkProbeWindow"));
        view.setVideoURI(Uri.parse("rtp://239.1.1.2:1234"));
        assertEquals(false, field(view, "mExpandedIjkProbeWindow"));
        view.release(true);
    }

    static Bundle metadata(String codec, int width, int height, int profile) {
        Bundle stream = new Bundle();
        stream.putString(IjkMediaMeta.IJKM_KEY_TYPE, "video");
        stream.putString(IjkMediaMeta.IJKM_KEY_CODEC_NAME, codec);
        stream.putString(IjkMediaMeta.IJKM_KEY_WIDTH, Integer.toString(width));
        stream.putString(IjkMediaMeta.IJKM_KEY_HEIGHT, Integer.toString(height));
        stream.putString(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID, Integer.toString(profile));
        ArrayList<Bundle> streams = new ArrayList<>();
        streams.add(stream);
        Bundle meta = new Bundle();
        meta.putParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS, streams);
        meta.putString(IjkMediaMeta.IJKM_KEY_VIDEO_STREAM, "0");
        return meta;
    }

    private static TestView view(String url) throws Exception {
        TestView view = new TestView(RuntimeEnvironment.getApplication());
        set(view, "mUri", Uri.parse(url));
        return view;
    }

    private static RecordingPlayer attach(TestView view) throws Exception {
        RecordingPlayer player = new RecordingPlayer();
        set(view, "mMediaPlayer", player.proxy);
        set(view, "mCurrentState", 1);
        Method attach = IjkVideoView.class.getDeclaredMethod("attachPlayerListeners", IMediaPlayer.class);
        attach.setAccessible(true);
        attach.invoke(view, player.proxy);
        return player;
    }

    private static Object field(TestView view, String name) throws Exception {
        Field field = IjkVideoView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(view);
    }

    private static void set(TestView view, String name, Object value) throws Exception {
        Field field = IjkVideoView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }

    private static class TestView extends IjkVideoView {
        Bundle meta;
        TestView(Context context) { super(context); }
        protected Bundle readIjkMediaMeta(IMediaPlayer player) { return meta; }
    }

    private static class RecordingPlayer {
        IMediaPlayer.OnPreparedListener prepared;
        IMediaPlayer.OnInfoListener info;
        int starts;
        boolean released;
        final IMediaPlayer proxy = (IMediaPlayer) Proxy.newProxyInstance(
                IMediaPlayer.class.getClassLoader(), new Class<?>[]{IMediaPlayer.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setOnPreparedListener": prepared = (IMediaPlayer.OnPreparedListener) args[0]; break;
                        case "setOnInfoListener": info = (IMediaPlayer.OnInfoListener) args[0]; break;
                        case "start": starts++; break;
                        case "release": released = true; break;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
                });
    }
}
