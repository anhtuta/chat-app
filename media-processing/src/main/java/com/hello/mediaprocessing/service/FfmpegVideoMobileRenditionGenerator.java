package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingVideoRenditionProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.exception.VideoRenditionGenerationException;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Uses ffmpeg to produce one smaller mobile-friendly MP4 rendition from the canonical playback asset.
 */
@Singleton
public class FfmpegVideoMobileRenditionGenerator implements VideoMobileRenditionGenerator {

    private static final Duration READER_JOIN_GRACE_AFTER_KILL = Duration.ofSeconds(2);

    private final MediaProcessingVideoRenditionProperties renditionProperties;

    public FfmpegVideoMobileRenditionGenerator(MediaProcessingVideoRenditionProperties renditionProperties) {
        this.renditionProperties = renditionProperties;
    }

    /**
     * Re-encodes a smaller MP4 rendition when the canonical asset is large enough to benefit from it.
     *
     * @param canonicalInputFile local canonical playback MP4
     * @param outputFile destination path for the smaller MP4
     * @param sourceMetadata original metadata used for skip decisions
     * @param canonicalObjectSize size of the canonical playback object in bytes
     * @return generated file path, or empty when the rendition is intentionally skipped
     */
    @Override
    public Optional<Path> generate(
            Path canonicalInputFile,
            Path outputFile,
            VideoMetadata sourceMetadata,
            long canonicalObjectSize) {
        if (shouldSkip(sourceMetadata, canonicalObjectSize)) {
            return Optional.empty();
        }

        try {
            Files.createDirectories(outputFile.getParent());
            Process process = new ProcessBuilder(buildCommand(canonicalInputFile, outputFile, sourceMetadata)).start();
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getInputStream()));
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getErrorStream()));

            Duration configuredTimeout = Duration.ofSeconds(renditionProperties.getTimeoutSeconds());
            boolean completed = process.waitFor(configuredTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                awaitReadersQuietly(stdoutFuture, stderrFuture);
                throw new VideoRenditionGenerationException(
                        MediaProcessingFailureReason.RENDITION_GENERATION_FAILED,
                        "ffmpeg timed out while generating a mobile rendition for " + canonicalInputFile);
            }

            String stderr = awaitReadersQuietly(stderrFuture);
            if (process.exitValue() != 0) {
                throw new VideoRenditionGenerationException(
                        MediaProcessingFailureReason.RENDITION_GENERATION_FAILED,
                        "ffmpeg failed while generating a mobile rendition for "
                                + canonicalInputFile + ": " + stderr.trim());
            }
            stdoutFuture.cancel(true);

            if (!Files.isRegularFile(outputFile) || Files.size(outputFile) <= 0) {
                throw new VideoRenditionGenerationException(
                        MediaProcessingFailureReason.RENDITION_GENERATION_FAILED,
                        "ffmpeg produced an empty mobile rendition for " + canonicalInputFile);
            }
            return Optional.of(outputFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoRenditionGenerationException(
                    MediaProcessingFailureReason.RENDITION_GENERATION_FAILED,
                    "Interrupted while generating a mobile rendition for " + canonicalInputFile,
                    e);
        } catch (IOException e) {
            throw new VideoRenditionGenerationException(
                    MediaProcessingFailureReason.RENDITION_GENERATION_FAILED,
                    "Failed to execute ffmpeg for mobile rendition generation on " + canonicalInputFile,
                    e);
        }
    }

    /**
     * Returns whether the smaller rendition should be skipped because the canonical asset is already small enough.
     *
     * @param sourceMetadata source metadata used to inspect duration and resolution
     * @param canonicalObjectSize size of the canonical playback object in bytes
     * @return {@code true} when Phase 9 should avoid generating a redundant mobile rendition
     */
    boolean shouldSkip(VideoMetadata sourceMetadata, long canonicalObjectSize) {
        if (sourceMetadata == null || sourceMetadata.height() == null || sourceMetadata.durationMillis() <= 0) {
            return true;
        }
        if (sourceMetadata.height() <= renditionProperties.getMaxHeight()) {
            return true;
        }
        if (sourceMetadata.durationMillis() < renditionProperties.getMinDurationSeconds() * 1000L) {
            return true;
        }
        return canonicalObjectSize < renditionProperties.getMinCanonicalSizeBytes();
    }

    /**
     * Builds the ffmpeg command for the smaller mobile-friendly MP4 rendition.
     *
     * @param canonicalInputFile canonical playback MP4 used as the source
     * @param outputFile destination MP4
     * @param sourceMetadata original metadata used to determine whether audio is present
     * @return command arguments passed to {@link ProcessBuilder}
     */
    List<String> buildCommand(Path canonicalInputFile, Path outputFile, VideoMetadata sourceMetadata) {
        List<String> command = new ArrayList<>();
        command.add(renditionProperties.getFfmpegPath());
        command.add("-y");
        command.add("-i");
        command.add(canonicalInputFile.toString());
        command.add("-vf");
        command.add("scale=-2:" + renditionProperties.getMaxHeight());
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(renditionProperties.getPreset());
        command.add("-crf");
        command.add(String.valueOf(renditionProperties.getVideoCrf()));
        command.add("-pix_fmt");
        command.add("yuv420p");
        boolean hasAudio = sourceMetadata.audioCodec() != null && !sourceMetadata.audioCodec().isBlank();
        if (hasAudio) {
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add(renditionProperties.getAudioBitrate());
        } else {
            command.add("-an");
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
