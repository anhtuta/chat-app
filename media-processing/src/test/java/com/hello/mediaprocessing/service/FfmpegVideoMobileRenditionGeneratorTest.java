package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoRenditionProperties;
import com.hello.mediaprocessing.model.VideoMetadata;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers skip decisions and command construction for the first mobile-friendly MP4 rendition generator.
 */
class FfmpegVideoMobileRenditionGeneratorTest {

    /**
     * Verifies that the smaller rendition is skipped when the source is already 480p or smaller.
     */
    @Test
    void generate_alreadySmallSource_skipsWithoutRunningFfmpeg() {
        FfmpegVideoMobileRenditionGenerator generator =
                new FfmpegVideoMobileRenditionGenerator(new MediaProcessingVideoRenditionProperties());

        assertThat(generator.generate(
                Path.of("/tmp/input.mp4"),
                Path.of("/tmp/output.480p.mp4"),
                new VideoMetadata(30_000L, 854, 480, "video/mp4", "mp4", "h264", "aac"),
                20L * 1024 * 1024)).isEmpty();
    }

    /**
     * Verifies that the smaller rendition is skipped when the canonical MP4 is too small to justify another derivative.
     */
    @Test
    void generate_smallCanonicalObject_skipsWithoutRunningFfmpeg() {
        FfmpegVideoMobileRenditionGenerator generator =
                new FfmpegVideoMobileRenditionGenerator(new MediaProcessingVideoRenditionProperties());

        assertThat(generator.generate(
                Path.of("/tmp/input.mp4"),
                Path.of("/tmp/output.480p.mp4"),
                new VideoMetadata(30_000L, 1920, 1080, "video/mp4", "mp4", "h264", "aac"),
                1024L)).isEmpty();
    }

    /**
     * Verifies that the ffmpeg command uses the expected 480p profile and fast-start MP4 flags.
     */
    @Test
    void buildCommand_uses480pCompatibilityProfile() {
        MediaProcessingVideoRenditionProperties properties = new MediaProcessingVideoRenditionProperties();
        FfmpegVideoMobileRenditionGenerator generator = new FfmpegVideoMobileRenditionGenerator(properties);

        assertThat(generator.buildCommand(
                Path.of("/tmp/input.mp4"),
                Path.of("/tmp/output.480p.mp4"),
                new VideoMetadata(30_000L, 1920, 1080, "video/mp4", "mp4", "h264", "aac")))
                        .containsSequence(
                                "ffmpeg",
                                "-y",
                                "-i",
                                "/tmp/input.mp4",
                                "-vf",
                                "scale=-2:480",
                                "-c:v",
                                "libx264")
                        .contains("+faststart")
                        .contains("/tmp/output.480p.mp4");
    }
}
