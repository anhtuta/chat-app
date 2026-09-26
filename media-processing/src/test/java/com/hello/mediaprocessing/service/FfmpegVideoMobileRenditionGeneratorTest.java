package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoRenditionProperties;
import com.hello.mediaprocessing.model.VideoMetadata;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
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
                                "-hide_banner",
                                "-loglevel",
                                "error",
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

    /**
     * Verifies diagnostic capture keeps only the configured cap and still consumes the rest of the stream.
     */
    @Test
    void readStreamToString_capsRetainedDiagnostics() {
        FfmpegVideoMobileRenditionGenerator generator =
                new FfmpegVideoMobileRenditionGenerator(new MediaProcessingVideoRenditionProperties());
        byte[] payload = new byte[FfmpegVideoMobileRenditionGenerator.MAX_DIAGNOSTIC_BYTES + 128];
        Arrays.fill(payload, (byte) 'a');

        String captured = generator.readStreamToString(new ByteArrayInputStream(payload));

        assertThat(captured.getBytes(StandardCharsets.UTF_8))
                .hasSize(FfmpegVideoMobileRenditionGenerator.MAX_DIAGNOSTIC_BYTES);
    }
}
