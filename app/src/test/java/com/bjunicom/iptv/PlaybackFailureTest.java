package com.bjunicom.iptv;

import com.bjunicom.iptv.player.PlaybackFailure;

import org.junit.Test;

import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.assertEquals;

public class PlaybackFailureTest {
    @Test
    public void onlyTransportErrorsAreNetworkFallbackCandidates() {
        assertEquals(PlaybackFailure.NETWORK,
                PlaybackFailure.fromLegacy(IMediaPlayer.MEDIA_ERROR_IO, 0));
        assertEquals(PlaybackFailure.FORMAT,
                PlaybackFailure.fromLegacy(IMediaPlayer.MEDIA_ERROR_MALFORMED, 0));
        assertEquals(PlaybackFailure.DECODER,
                PlaybackFailure.fromLegacy(IMediaPlayer.MEDIA_ERROR_UNKNOWN, -22));
    }
}
