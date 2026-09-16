package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoTranscodeProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.VideoTranscodeMode;
import com.hello.mediaprocessing.exception.VideoTranscodeException;
import com.hello.mediaprocessing.model.VideoMetadata;
import com.hello.mediaprocessing.model.VideoTranscodeResult;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Uses ffmpeg to remux or re-encode source videos into a chat-friendly MP4.
 */
@Singleton
public class FfmpegVideoTranscoder implements VideoTranscoder {

    private static final Duration READER_JOIN_GRACE_AFTER_KILL = Duration.ofSeconds(2);

    private final VideoTranscodePlanner planner;
    private final MediaProcessingVideoTranscodeProperties transcodeProperties;

    public FfmpegVideoTranscoder(
            VideoTranscodePlanner planner, MediaProcessingVideoTranscodeProperties transcodeProperties) {
        this.planner = planner;
        this.transcodeProperties = transcodeProperties;
    }

    /**
     * Reuses a canonical MP4, remuxes compatible codecs, or re-encodes to H.264 + AAC.
     *
     * @param sourceFile downloaded original video
     * @param outputFile workspace path for a derived MP4 when conversion is required
     * @param sourceMetadata probed metadata used to choose the conversion mode
     * @return local playback file and the mode used to produce it
     */
    @Override
    public VideoTranscodeResult transcode(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
        VideoTranscodeMode mode = planner.plan(sourceMetadata);
        if (mode == VideoTranscodeMode.REUSE_ORIGINAL) {
            return new VideoTranscodeResult(mode, sourceFile);
        }

        try {
            Files.createDirectories(outputFile.getParent());
            Process process = new ProcessBuilder(buildCommand(mode, sourceFile, outputFile, sourceMetadata)).start();
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getInputStream()));
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getErrorStream()));

            Duration configuredTimeout = Duration.ofSeconds(transcodeProperties.getTimeoutSeconds());
            boolean completed = process.waitFor(configuredTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                awaitReadersQuietly(stdoutFuture, stderrFuture);
                throw new VideoTranscodeException(
                        MediaProcessingFailureReason.TRANSCODE_FAILED,
                        "ffmpeg timed out for file " + sourceFile);
            }

            String stderr = awaitReadersQuietly(stderrFuture);
            if (process.exitValue() != 0) {
                throw new VideoTranscodeException(
                        MediaProcessingFailureReason.TRANSCODE_FAILED,
                        "ffmpeg failed for file " + sourceFile + ": " + stderr.trim());
            }
            stdoutFuture.cancel(true);

            if (!Files.isRegularFile(outputFile) || Files.size(outputFile) <= 0) {
                throw new VideoTranscodeException(
                        MediaProcessingFailureReason.TRANSCODE_FAILED,
                        "ffmpeg produced an empty playback file for " + sourceFile);
            }
            return new VideoTranscodeResult(mode, outputFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoTranscodeException(
                    MediaProcessingFailureReason.TRANSCODE_FAILED,
                    "Interrupted while transcoding " + sourceFile,
                    e);
        } catch (IOException e) {
            throw new VideoTranscodeException(
                    MediaProcessingFailureReason.TRANSCODE_FAILED,
                    "Failed to execute ffmpeg for " + sourceFile,
                    e);
        }
    }

    /**
     * Builds the ffmpeg command for remux or re-encode into MP4 with fast-start headers.
     *
     * @param mode remux or re-encode
     * @param sourceFile input file
     * @param outputFile destination MP4
     * @param sourceMetadata probed source metadata, used to drop missing audio tracks
     * @return command arguments passed to {@link ProcessBuilder}
     */
    List<String> buildCommand(VideoTranscodeMode mode, Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
        List<String> command = new ArrayList<>();
        command.add(transcodeProperties.getFfmpegPath());
        command.add("-y");
        command.add("-i");
        command.add(sourceFile.toString());
        boolean hasAudio = sourceMetadata.audioCodec() != null && !sourceMetadata.audioCodec().isBlank();
        if (mode == VideoTranscodeMode.REMUX) {
            if (hasAudio) {
                command.add("-c");
                command.add("copy");
            } else {
                command.add("-c:v");
                command.add("copy");
                command.add("-an");
            }
        } else {
            command.add("-c:v");
            command.add("libx264");
            command.add("-preset");
            command.add(transcodeProperties.getPreset());
            command.add("-crf");
            command.add(String.valueOf(transcodeProperties.getVideoCrf()));
            command.add("-pix_fmt");
            command.add("yuv420p");
            if (hasAudio) {
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add(transcodeProperties.getAudioBitrate());
            } else {
                command.add("-an");
            }
        }
        command.add("-movflags");
        command.add("+faststart");
        command.add(outputFile.toString());
        return command;
    }

    /**
     * Reads an ffmpeg stream to completion on a background thread.
     *
     * @param inputStream process stream to drain
     * @return captured stream contents
     */
    private String readStreamToString(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Drains leftover ffmpeg streams after a timeout or failure without failing the caller.
     *
     * @param stdoutFuture task draining standard output
     * @param stderrFuture task draining standard error
     */
    private void awaitReadersQuietly(CompletableFuture<String> stdoutFuture, CompletableFuture<String> stderrFuture) {
        awaitReadersQuietly(stdoutFuture);
        awaitReadersQuietly(stderrFuture);
    }

    /**
     * Waits briefly for a reader task so ffmpeg cannot fill a pipe after the worker has moved on.
     *
     * @param readerFuture task draining a process stream
     * @return captured stream contents, or an empty string when unavailable
     */
    private String awaitReadersQuietly(CompletableFuture<String> readerFuture) {
        try {
            return readerFuture.get(READER_JOIN_GRACE_AFTER_KILL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            readerFuture.cancel(true);
            return "";
        }
    }
}
