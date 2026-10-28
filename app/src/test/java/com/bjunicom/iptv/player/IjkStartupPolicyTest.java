package com.bjunicom.iptv.player;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import tv.danmaku.ijk.media.player.IjkMediaMeta;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class IjkStartupPolicyTest {
    @Test public void alignedInitialProbeRemainsSmallAndExpandedProbeUsesActualCodec() {
        TsCodecDescriptor hd = new TsCodecDescriptor("h264", "mp2", 256, 257, 258,
                0x1b, 3, true, 90000, 0);
        TsCodecDescriptor hevc = TsCodecDescriptor.createDefault(true);
        assertEquals(96 * 1024L, IjkStartupPolicy.forStream(hd, false).probeSizeBytes);
        assertEquals(120000L, IjkStartupPolicy.forStream(hd, false).analyzeDurationUs);
        assertEquals(1536 * 1024L, IjkStartupPolicy.forStream(hevc, false).probeSizeBytes);
        assertEquals(1500000L, IjkStartupPolicy.forStream(hevc, false).analyzeDurationUs);
        assertEquals(8 * 1024 * 1024L, IjkStartupPolicy.forStream(hd, true).probeSizeBytes);
        assertEquals(16 * 1024 * 1024L, IjkStartupPolicy.forStream(hevc, true).probeSizeBytes);
    }

    @Test public void multicastDetectionSupportsProxyAndCustomPortsWithoutMatchingQueryStrings() {
        assertTrue(IjkStartupPolicy.isMulticast(null, Uri.parse("udp://@239.1.1.1:1234")));
        assertTrue(IjkStartupPolicy.isMulticast(null, Uri.parse("http://router/rtp/239.1.1.1:8008")));
        assertTrue(IjkStartupPolicy.isMulticast("rtp://239.1.1.1:1234", Uri.parse("http://router/live")));
        assertFalse(IjkStartupPolicy.isMulticast(null, Uri.parse("http://server/?next=rtp://239.1.1.1:8008")));
    }

    @Test public void identifyingMp2DoesNotMakeAnIncompleteVideoStreamReady() {
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 0, 0, -99), false));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 0, 100), false));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 0, 1080, 100), false));
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 1080, 100), false));
    }

    @Test public void avcNeedsAParsedProfileEvenWhenDimensionsArePresent() {
        Bundle unknown = metadata("h264", 1920, 1080, -99);
        assertNotNull(IjkStartupPolicy.videoParametersIssue(unknown, false));
        unknown.<Bundle>getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS).get(0)
                .remove(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID);
        assertNotNull(IjkStartupPolicy.videoParametersIssue(unknown, false));
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 1080, 578), false));
        // A known unsupported hardware profile is not a probing failure.
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 1080, 110), false));
    }

    @Test public void selectedVideoIsValidatedEvenIfAnotherVideoStreamIsComplete() {
        Bundle meta = metadata("h264", 1920, 1080, 100);
        ArrayList<Bundle> streams = meta.getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS);
        streams.add(metadata("h264", 0, 0, -99)
                .<Bundle>getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS).get(0));
        meta.putString(IjkMediaMeta.IJKM_KEY_VIDEO_STREAM, "2");
        assertNotNull(IjkStartupPolicy.videoParametersIssue(meta, false));
    }

    @Test public void otherVideoCodecsAndAudioOnlyStreamsDoNotRequireAnAvcProfile() {
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("hevc", 3840, 2160, -99), false));
        assertNull(IjkStartupPolicy.videoParametersIssue(audioOnlyMetadata(), false));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(null, false));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(new Bundle(), false));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("", 1920, 1080, 100), false));
    }

    static Bundle metadata(String codec, int width, int height, int profile) {
        Bundle meta = audioOnlyMetadata();
        Bundle video = new Bundle();
        video.putString(IjkMediaMeta.IJKM_KEY_TYPE, "video");
        video.putString(IjkMediaMeta.IJKM_KEY_CODEC_NAME, codec);
        video.putString(IjkMediaMeta.IJKM_KEY_WIDTH, Integer.toString(width));
        video.putString(IjkMediaMeta.IJKM_KEY_HEIGHT, Integer.toString(height));
        video.putString(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID, Integer.toString(profile));
        meta.<Bundle>getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS).add(0, video);
        meta.putString(IjkMediaMeta.IJKM_KEY_VIDEO_STREAM, "0");
        meta.putString(IjkMediaMeta.IJKM_KEY_AUDIO_STREAM, "1");
        return meta;
    }

    static Bundle audioOnlyMetadata() {
        Bundle meta = new Bundle();
        Bundle audio = new Bundle();
        audio.putString(IjkMediaMeta.IJKM_KEY_TYPE, "audio");
        audio.putString(IjkMediaMeta.IJKM_KEY_CODEC_NAME, "mp2");
        audio.putString(IjkMediaMeta.IJKM_KEY_SAMPLE_RATE, "48000");
        ArrayList<Bundle> streams = new ArrayList<>();
        streams.add(audio);
        meta.putParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS, streams);
        meta.putString(IjkMediaMeta.IJKM_KEY_VIDEO_STREAM, "-1");
        meta.putString(IjkMediaMeta.IJKM_KEY_AUDIO_STREAM, "0");
        return meta;
    }
}
