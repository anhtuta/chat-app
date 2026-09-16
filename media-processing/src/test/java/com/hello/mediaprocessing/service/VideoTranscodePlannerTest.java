package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.constant.VideoTranscodeMode;
import com.hello.mediaprocessing.exception.VideoTranscodeException;
import com.hello.mediaprocessing.model.VideoMetadata;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers conversion-mode selection for the MP4/MOV/WebM allowlist.
 */
class VideoTranscodePlannerTest {

    private final VideoTranscodePlanner planner = new VideoTranscodePlanner();

    /**
     * Verifies that H.264 + AAC MP4 files are reused instead of duplicated.
     */
    @Test
    void plan_canonicalMp4_reusesOriginal() {
        assertThat(planner.plan(metadata("video/mp4", "h264", "aac"))).isEqualTo(VideoTranscodeMode.REUSE_ORIGINAL);
    }

    /**
     * Verifies that H.264 + AAC MOV files are remuxed into MP4 without re-encoding.
     */
    @Test
    void plan_movH264Aac_remuxes() {
        assertThat(planner.plan(metadata("video/quicktime", "h264", "aac"))).isEqualTo(VideoTranscodeMode.REMUX);
    }

    /**
     * Verifies that WebM VP9 sources are fully re-encoded.
     */
    @Test
    void plan_webmVp9_reencodes() {
        assertThat(planner.plan(metadata("video/webm", "vp9", "opus"))).isEqualTo(VideoTranscodeMode.REENCODE);
    }

    /**
     * Verifies that containers outside the allowlist fail before ffmpeg runs.
     */
    @Test
    void plan_avi_rejected() {
        assertThatThrownBy(() -> planner.plan(metadata("video/x-msvideo", "mpeg4", "mp3")))
                .isInstanceOf(VideoTranscodeException.class)
                .hasMessageContaining("allowlist");
    }

    /**
     * Builds metadata used to exercise planner decisions.
     *
     * @param mimeType detected MIME type
     * @param videoCodec probed video codec
     * @param audioCodec probed audio codec
     * @return metadata payload for the test case
     */
    private VideoMetadata metadata(String mimeType, String videoCodec, String audioCodec) {
        return new VideoMetadata(1_000L, 640, 360, mimeType, "test", videoCodec, audioCodec);
    }
}
