package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.VideoTranscodeMode;
import com.hello.mediaprocessing.exception.VideoTranscodeException;
import com.hello.mediaprocessing.model.VideoMetadata;
import jakarta.inject.Singleton;
import lombok.NonNull;
import java.util.Locale;
import java.util.Set;

/**
 * Chooses whether a source video can be reused, remuxed, or must be re-encoded for chat playback.
 */
@Singleton
public class VideoTranscodePlanner {

    private static final Set<String> ACCEPTED_MIME_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");

    /**
     * Selects the cheapest conversion that still yields H.264 + AAC in MP4.
     *
     * @param metadata probed source metadata
     * @return transcode strategy for the source
     */
    public VideoTranscodeMode plan(@NonNull VideoMetadata metadata) {
        String mimeType = normalizeMimeType(metadata.detectedMimeType());
        if (!ACCEPTED_MIME_TYPES.contains(mimeType)) {
            throw new VideoTranscodeException(
                    MediaProcessingFailureReason.UNSUPPORTED_VIDEO_FORMAT,
                    "Video MIME type is not in the MP4/MOV/WebM allowlist: " + mimeType);
        }

        boolean h264 = isH264(metadata.videoCodec());
        boolean aacOrSilent = isAacOrMissing(metadata.audioCodec());
        if (h264 && aacOrSilent && "video/mp4".equals(mimeType)) {
            return VideoTranscodeMode.REUSE_ORIGINAL;
        }
        if (h264 && aacOrSilent) {
            return VideoTranscodeMode.REMUX;
        }
        return VideoTranscodeMode.REENCODE;
    }

    /**
     * Strips codec parameters and lower-cases a MIME type for allowlist comparison.
     *
     * @param mimeType raw MIME type from upload or probe
     * @return normalized type, or an empty string when missing
     */
    static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "";
        }
        int separator = mimeType.indexOf(';');
        String typeOnly = separator < 0 ? mimeType : mimeType.substring(0, separator);
        return typeOnly.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns whether the probed video codec is H.264 / AVC.
     *
     * @param codecName codec name reported by ffprobe
     * @return {@code true} when the stream is already H.264
     */
    static boolean isH264(String codecName) {
        if (codecName == null || codecName.isBlank()) {
            return false;
        }
        String normalized = codecName.trim().toLowerCase(Locale.ROOT);
        return "h264".equals(normalized) || "avc".equals(normalized) || "avc1".equals(normalized);
    }

    /**
     * Returns whether audio is already AAC or the file has no audio track.
     *
     * @param codecName codec name reported by ffprobe, or {@code null} when there is no audio
     * @return {@code true} when audio does not need re-encoding for chat playback
     */
    static boolean isAacOrMissing(String codecName) {
        if (codecName == null || codecName.isBlank()) {
            return true;
        }
        String normalized = codecName.trim().toLowerCase(Locale.ROOT);
        return "aac".equals(normalized) || "mp4a".equals(normalized);
    }
}
