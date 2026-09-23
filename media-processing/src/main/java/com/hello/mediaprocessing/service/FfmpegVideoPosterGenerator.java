package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoPosterProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.exception.VideoPosterGenerationException;
import com.hello.mediaprocessing.model.VideoMetadata;
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
 * Uses ffmpeg to capture one still JPEG poster frame from a video source.
 */
@Singleton
public class FfmpegVideoPosterGenerator implements VideoPosterGenerator {

    private static final Duration READER_JOIN_GRACE_AFTER_KILL = Duration.ofSeconds(2);

    private final MediaProcessingVideoPosterProperties posterProperties;

    public FfmpegVideoPosterGenerator(MediaProcessingVideoPosterProperties posterProperties) {
        this.posterProperties = posterProperties;
    }

    /**
     * Captures a single JPEG poster at a stable timestamp near the start of the video.
     *
     * @param sourceFile downloaded original video
     * @param outputFile workspace path for the derived JPEG
     * @param sourceMetadata probed source metadata used to clamp the capture timestamp
     * @return generated poster file path
     */
    @Override
    public Path generate(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
        try {
            Files.createDirectories(outputFile.getParent());
            Process process = new ProcessBuilder(buildCommand(sourceFile, outputFile, sourceMetadata)).start();
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getInputStream()));
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getErrorStream()));

            Duration configuredTimeout = Duration.ofSeconds(posterProperties.getTimeoutSeconds());
            boolean completed = process.waitFor(configuredTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                awaitReadersQuietly(stdoutFuture, stderrFuture);
                throw new VideoPosterGenerationException(
                        MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                        "ffmpeg timed out while generating a poster for " + sourceFile);
            }

            String stderr = awaitReadersQuietly(stderrFuture);
            if (process.exitValue() != 0) {
                throw new VideoPosterGenerationException(
                        MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                        "ffmpeg failed while generating a poster for " + sourceFile + ": " + stderr.trim());
            }
            stdoutFuture.cancel(true);

            if (!Files.isRegularFile(outputFile) || Files.size(outputFile) <= 0) {
                throw new VideoPosterGenerationException(
                        MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                        "ffmpeg produced an empty poster file for " + sourceFile);
            }
            return outputFile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoPosterGenerationException(
                    MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                    "Interrupted while generating a poster for " + sourceFile,
                    e);
        } catch (IOException e) {
            throw new VideoPosterGenerationException(
                    MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                    "Failed to execute ffmpeg for poster generation on " + sourceFile,
                    e);
        }
    }

    /**
     * Builds the ffmpeg command for single-frame JPEG extraction.
     *
     * @param sourceFile input video
     * @param outputFile destination JPEG
     * @param sourceMetadata metadata used to choose the capture timestamp
     * @return command arguments passed to {@link ProcessBuilder}
     */
    List<String> buildCommand(Path sourceFile, Path outputFile, VideoMetadata sourceMetadata) {
        List<String> command = new ArrayList<>();
        command.add(posterProperties.getFfmpegPath());
        command.add("-y");
        command.add("-ss");
        command.add(formatCaptureSecond(resolveCaptureSecond(sourceMetadata)));
        command.add("-i");
        command.add(sourceFile.toString());
        command.add("-frames:v");
        command.add("1");
        command.add("-q:v");
        command.add(String.valueOf(posterProperties.getJpegQuality()));
        command.add("-vf");
        command.add("scale=min(" + posterProperties.getMaxWidth() + "\\,iw):-2");
        command.add(outputFile.toString());
        return command;
    }

    /**
     * Chooses a stable capture timestamp near the start while avoiding timestamps beyond the video duration.
     *
     * @param sourceMetadata probed metadata for the source
     * @return capture timestamp in seconds
     */
    private double resolveCaptureSecond(VideoMetadata sourceMetadata) {
        double configured = posterProperties.getCaptureSecond();
        if (sourceMetadata == null || sourceMetadata.durationMillis() <= 0) {
            return configured;
        }
        double durationSeconds = sourceMetadata.durationMillis() / 1000.0d;
        double upperBound = Math.max(0.0d, durationSeconds / 3.0d);
        return Math.min(configured, upperBound);
    }

    /**
     * Formats the ffmpeg seek timestamp with millisecond precision.
     *
     * @param captureSecond numeric capture timestamp
     * @return string argument suitable for {@code -ss}
     */
    private String formatCaptureSecond(double captureSecond) {
        return String.format(java.util.Locale.ROOT, "%.3f", Math.max(0.0d, captureSecond));
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
