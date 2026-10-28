package com.bjunicom.iptv.player;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import com.bjunicom.iptv.network.TransportRouteCache;

import tv.danmaku.ijk.media.player.IjkMediaMeta;

/** Keeps the fast aligned probe, with one larger budget for incomplete multicast metadata. */
final class IjkStartupPolicy {
    final long probeSizeBytes;
    final long analyzeDurationUs;

    private IjkStartupPolicy(long bytes, long durationUs) {
        probeSizeBytes = bytes;
        analyzeDurationUs = durationUs;
    }

    static boolean isMulticast(String rawUrl, Uri uri) {
        return TransportRouteCache.multicastTarget(uri) != null
                || (!TextUtils.isEmpty(rawUrl)
                && TransportRouteCache.multicastTarget(Uri.parse(rawUrl)) != null);
    }

    static IjkStartupPolicy forStream(TsCodecDescriptor descriptor, boolean expanded) {
        boolean hevc = descriptor != null && descriptor.isHevc();
        if (expanded) {
            // Upper bounds, not fixed delays; use actual codec information rather than a port heuristic.
            return new IjkStartupPolicy((hevc ? 16 : 8) * 1024 * 1024L,
                    hevc ? 8000000L : 6000000L);
        }
        boolean aligned = descriptor != null && descriptor.keyframeAligned;
        return new IjkStartupPolicy(hevc ? 1536 * 1024L : (aligned ? 96 : 256) * 1024L,
                hevc ? 1500000L : (aligned ? 120000L : 400000L));
    }

    /** A successful find_stream_info may only mean that audio was identified. */
    static String videoParametersIssue(Bundle bundle, boolean expectsVideo) {
        IjkMediaMeta meta = IjkMediaMeta.parse(bundle);
        if (meta == null || meta.mStreams.isEmpty()) return "missing stream metadata";
        IjkMediaMeta.IjkStreamMeta video = meta.mVideoStream;
        boolean hasAudio = false;
        for (IjkMediaMeta.IjkStreamMeta stream : meta.mStreams) {
            if ("video".equals(stream.mType) && video == null) video = stream;
            if ("audio".equals(stream.mType)) hasAudio = true;
        }
        if (video == null) return hasAudio && !expectsVideo ? null : "missing video stream";
        if (video.mWidth <= 0 || video.mHeight <= 0) {
            return "invalid video size " + video.mWidth + "x" + video.mHeight;
        }
        if (TextUtils.isEmpty(video.mCodecName)) return "missing video codec";
        // Java metadata does not expose SPS/PPS bytes. Width/height and the AVC profile
        // reveal whether FFmpeg parsed the parameters needed by Ijk's MediaCodec selector.
        if ("h264".equalsIgnoreCase(video.mCodecName)
                && video.getInt(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID, -99) <= 0) {
            return "unknown H.264 profile";
        }
        return null;
    }
}
