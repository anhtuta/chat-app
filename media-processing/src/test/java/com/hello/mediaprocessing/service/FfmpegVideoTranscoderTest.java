package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoTranscodeProperties;
import com.hello.mediaprocessing.constant.VideoTranscodeMode;
import com.hello.mediaprocessing.model.VideoMetadata;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers ffmpeg command construction for remux and re-encode without invoking ffmpeg.
 */
class FfmpegVideoTranscoderTest {

    /**
     * Verifies remux copies existing H.264 + AAC streams into MP4 with fast-start.
     */
    @Test
    void buildCommand_remuxWithAudio_copiesStreams() {
        FfmpegVideoTranscoder transcoder = newTranscoder();
        List<String> command = transcoder.buildCommand(
                VideoTranscodeMode.REMUX,
                Path.of("/tmp/in.mov"),
                Path.of("/tmp/out.mp4"),
                metadata("aac"));

        assertThat(command)
                .contains("ffmpeg", "-i", "/tmp/in.mov", "-c", "copy", "-movflags", "+faststart", "/tmp/out.mp4");
        assertThat(command).doesNotContain("libx264");
    }

    /**
     * Verifies re-encode uses H.264 + AAC and drops nothing when audio is present.
     */
    @Test
    void buildCommand_reencodeWithAudio_usesH264Aac() {
        FfmpegVideoTranscoder transcoder = newTranscoder();
        List<String> command = transcoder.buildCommand(
                VideoTranscodeMode.REENCODE,
                Path.of("/tmp/in.webm"),
                Path.of("/tmp/out.mp4"),
                metadata("opus"));

        assertThat(command).contains("libx264", "aac", "yuv420p", "+faststart");
        assertThat(command).doesNotContain("-an");
    }

    /**
     * Verifies video-only sources omit an audio encode.
     */
    @Test
    void buildCommand_reencodeWithoutAudio_omitsAudio() {
        FfmpegVideoTranscoder transcoder = newTranscoder();
        List<String> command = transcoder.buildCommand(
                VideoTranscodeMode.REENCODE,
                Path.of("/tmp/in.mp4"),
                Path.of("/tmp/out.mp4"),
                metadata(null));

        assertThat(command).contains("-an");
        assertThat(command).doesNotContain("aac");
    }

    /**
     * Creates a transcoder with default ffmpeg settings.
     *
     * @return transcoder used only for command-line assertions
     */
    private FfmpegVideoTranscoder newTranscoder() {
        return new FfmpegVideoTranscoder(new VideoTranscodePlanner(), new MediaProcessingVideoTranscodeProperties());
    }

    /**
     * Builds metadata with a specific audio codec for command tests.
     *
     * @param audioCodec audio codec, or {@code null} for video-only files
     * @return metadata payload
     */
    private VideoMetadata metadata(String audioCodec) {
        return new VideoMetadata(1_000L, 640, 360, "video/mp4", "mp4", "h264", audioCodec);
    }
}
