package com.bjunicom.iptv.player;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/** Classifies playback errors before the UI chooses a transport fallback. */
public enum PlaybackFailure {
    NETWORK,
    FORMAT,
    DECODER,
    UNKNOWN;

    public static PlaybackFailure fromLegacy(int what, int extra) {
        if (what == IMediaPlayer.MEDIA_ERROR_IO || extra == IMediaPlayer.MEDIA_ERROR_IO
                || what == IMediaPlayer.MEDIA_ERROR_TIMED_OUT
                || extra == IMediaPlayer.MEDIA_ERROR_TIMED_OUT
                || extra == -5 || extra == -110 || extra == -111
                || extra == -101 || extra == -113) {
            return NETWORK;
        }
        if (what == IMediaPlayer.MEDIA_ERROR_UNSUPPORTED
                || extra == IMediaPlayer.MEDIA_ERROR_UNSUPPORTED
                || what == IMediaPlayer.MEDIA_ERROR_MALFORMED
                || extra == IMediaPlayer.MEDIA_ERROR_MALFORMED) {
            return FORMAT;
        }
        if (extra == -22 || extra == -38 || extra == -1010) {
            return DECODER;
        }
        return UNKNOWN;
    }
}
