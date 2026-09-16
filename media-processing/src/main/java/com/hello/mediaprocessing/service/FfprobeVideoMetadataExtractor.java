package com.hello.mediaprocessing.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.mediaprocessing.config.MediaProcessingVideoMetadataProperties;
import com.hello.mediaprocessing.exception.VideoMetadataExtractionException;
import com.hello.mediaprocessing.model.VideoMetadata;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Uses ffprobe to extract metadata from downloaded local video files.
 */
@Singleton
public class FfprobeVideoMetadataExtractor implements VideoMetadataExtractor {

    private static final Duration READER_JOIN_GRACE_AFTER_KILL = Duration.ofSeconds(2);

    private final MediaProcessingVideoMetadataProperties videoMetadataProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FfprobeVideoMetadataExtractor(MediaProcessingVideoMetadataProperties videoMetadataProperties) {
        this.videoMetadataProperties = videoMetadataProperties;
    }

    /**
     * Runs ffprobe against a local video file and converts the result into a normalized metadata record.
     *
     * @param localFile local video file path inside the worker workspace
     * @param fallbackMimeType MIME type captured earlier in the upload flow
     * @return normalized video metadata
     */
    @Override
    public VideoMetadata extract(Path localFile, String fallbackMimeType) {
        try {
            Process process = new ProcessBuilder(buildCommand(localFile)).start();
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getInputStream()));
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readStreamToString(process.getErrorStream()));

            long startNanos = System.nanoTime();
            Duration configuredTimeout = Duration.ofSeconds(videoMetadataProperties.getTimeoutSeconds());
            boolean completed = process.waitFor(configuredTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                ProbeOutput output = awaitReaders(
                        stdoutFuture,
                        stderrFuture,
                        readerJoinTimeoutAfterKill(startNanos, configuredTimeout),
                        true);
                throw new VideoMetadataExtractionException(
                        "ffprobe timed out for file " + localFile + formatProbeDiagnostics(output));
            }

            ProbeOutput output = awaitReaders(
                    stdoutFuture,
                    stderrFuture,
                    remainingReaderTimeout(startNanos, configuredTimeout),
                    false);
            if (process.exitValue() != 0) {
                throw new VideoMetadataExtractionException(
                        "ffprobe failed for file " + localFile + ": " + output.stderr().trim());
            }

            return parseProbeOutput(output.stdout(), resolveDetectedMimeType(localFile, fallbackMimeType));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoMetadataExtractionException("Interrupted while probing video metadata for " + localFile, e);
        } catch (IOException e) {
            throw new VideoMetadataExtractionException("Failed to execute ffprobe for " + localFile, e);
        }
    }

    /**
     * Reads an ffprobe stream to completion on a background thread.
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
     * Waits for both ffprobe reader tasks to finish and returns the captured output.
     *
     * @param stdoutFuture task draining standard output
     * @param stderrFuture task draining standard error
     * @param timeout shared maximum time to wait for both reader tasks
     * @param lenientDiagnostics when {@code true}, reader timeouts return empty output instead of failing
     * @return captured stdout and stderr from the probe process
     */
    private ProbeOutput awaitReaders(
            CompletableFuture<String> stdoutFuture,
            CompletableFuture<String> stderrFuture,
            Duration timeout,
            boolean lenientDiagnostics) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String stdout = awaitReader(stdoutFuture, timeout, lenientDiagnostics);
        String stderr = awaitReader(stderrFuture, remainingDuration(deadlineNanos), lenientDiagnostics);
        return new ProbeOutput(stdout, stderr);
    }

    /**
     * Returns the remaining wait budget before a reader deadline is reached.
     *
     * @param deadlineNanos reader deadline in nanoseconds
     * @return remaining duration, clamped to zero when the budget is exhausted
     */
    private Duration remainingDuration(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(remainingNanos);
    }

    /**
     * Waits for a single ffprobe reader task to finish within the allotted time.
     *
     * @param readerFuture task draining a process stream
     * @param timeout maximum time to wait for the reader task
     * @param lenientDiagnostics when {@code true}, timeouts are treated as unavailable diagnostics
     * @return captured stream contents, or an empty string when diagnostics are unavailable
     */
    private String awaitReader(CompletableFuture<String> readerFuture, Duration timeout, boolean lenientDiagnostics) {
        long waitMillis = Math.max(0L, timeout.toMillis());
        if (waitMillis == 0L) {
            readerFuture.cancel(true);
            if (lenientDiagnostics) {
                return "";
            }
            throw new VideoMetadataExtractionException("Timed out while reading ffprobe output");
        }

        try {
            return readerFuture.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            readerFuture.cancel(true);
            if (lenientDiagnostics) {
                return "";
            }
            throw new VideoMetadataExtractionException("Timed out while reading ffprobe output", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            readerFuture.cancel(true);
            if (lenientDiagnostics) {
                return "";
            }
            throw new VideoMetadataExtractionException("Interrupted while reading ffprobe output", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof UncheckedIOException uncheckedIOException) {
                throw new VideoMetadataExtractionException(
                        "Failed to read ffprobe output",
                        uncheckedIOException.getCause());
            }
            throw new VideoMetadataExtractionException("Failed to read ffprobe output", cause);
        }
    }

    /**
     * Returns the remaining reader wait budget after ffprobe is forcibly terminated.
     *
     * @param startNanos process start timestamp in nanoseconds
     * @param configuredTimeout configured ffprobe timeout
     * @return bounded wait duration for draining process streams after a kill
     */
    private Duration readerJoinTimeoutAfterKill(long startNanos, Duration configuredTimeout) {
        Duration remaining = configuredTimeout.minusNanos(System.nanoTime() - startNanos);
        if (remaining.isNegative() || remaining.isZero()) {
            return READER_JOIN_GRACE_AFTER_KILL;
        }
        return remaining.compareTo(READER_JOIN_GRACE_AFTER_KILL) < 0 ? remaining : READER_JOIN_GRACE_AFTER_KILL;
    }

    /**
     * Returns the remaining reader wait budget for a process that exited within the configured timeout.
     *
     * @param startNanos process start timestamp in nanoseconds
     * @param configuredTimeout configured ffprobe timeout
     * @return remaining wait duration for draining process streams
     */
    private Duration remainingReaderTimeout(long startNanos, Duration configuredTimeout) {
        Duration remaining = configuredTimeout.minusNanos(System.nanoTime() - startNanos);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        return remaining;
    }

    /**
     * Formats captured ffprobe diagnostics for timeout failures.
     *
     * @param output captured stdout and stderr from the probe process
     * @return diagnostic suffix suitable for exception messages
     */
    private String formatProbeDiagnostics(ProbeOutput output) {
        if (!output.stderr().isBlank()) {
            return ": " + output.stderr().trim();
        }
        if (!output.stdout().isBlank()) {
            return " (stdout: " + output.stdout().trim() + ")";
        }
        return "";
    }

    /**
     * Parses ffprobe JSON output into the simplified metadata model used by the worker.
     *
     * @param stdout ffprobe JSON output
     * @param detectedMimeType detected or fallback MIME type for the local file
     * @return normalized metadata extracted from the probe output
     */
    VideoMetadata parseProbeOutput(String stdout, String detectedMimeType) {
        try {
            FfprobeResponse response = objectMapper.readValue(stdout, FfprobeResponse.class);
            FfprobeStream videoStream = response.streams() == null
                    ? null
                    : response.streams().stream()
                            .filter(stream -> "video".equals(stream.codec_type()))
                            .findFirst()
                            .orElse(null);
            FfprobeStream audioStream = response.streams() == null
                    ? null
                    : response.streams().stream()
                            .filter(stream -> "audio".equals(stream.codec_type()))
                            .findFirst()
                            .orElse(null);
            long durationMillis = parseDurationMillis(response.format() == null ? null : response.format().duration());
            if (videoStream == null) {
                throw new VideoMetadataExtractionException("ffprobe did not return a video stream");
            }

            return new VideoMetadata(
                    durationMillis,
                    videoStream.width(),
                    videoStream.height(),
                    detectedMimeType,
                    response.format() == null ? null : response.format().format_name(),
                    videoStream.codec_name(),
                    audioStream == null ? null : audioStream.codec_name());
        } catch (IOException e) {
            throw new VideoMetadataExtractionException("Failed to parse ffprobe JSON output", e);
        }
    }

    /**
     * Builds the ffprobe command line used for the current video file.
     *
     * @param localFile local video file path inside the worker workspace
     * @return command arguments passed to {@link ProcessBuilder}
     */
    private List<String> buildCommand(Path localFile) {
        return List.of(
                videoMetadataProperties.getFfprobePath(),
                "-v",
                "error",
                "-print_format",
                "json",
                "-show_streams",
                "-show_format",
                localFile.toString());
    }

    /**
     * Resolves a best-effort MIME type for the local file, preferring the filesystem probe over the upload fallback.
     *
     * @param localFile local video file path inside the worker workspace
     * @param fallbackMimeType MIME type captured earlier in the upload flow
     * @return best-effort detected MIME type
     */
    private String resolveDetectedMimeType(Path localFile, String fallbackMimeType) {
        try {
            return Optional.ofNullable(Files.probeContentType(localFile))
                    .filter(value -> !value.isBlank())
                    .orElse(fallbackMimeType);
        } catch (IOException e) {
            return fallbackMimeType;
        }
    }

    /**
     * Converts ffprobe's decimal-second duration string into milliseconds.
     *
     * @param durationString duration value reported by ffprobe
     * @return duration in milliseconds
     * @throws VideoMetadataExtractionException when the duration is missing, unparsable, non-finite, or negative
     */
    private long parseDurationMillis(String durationString) {
        if (durationString == null || durationString.isBlank()) {
            throw new VideoMetadataExtractionException("ffprobe returned a missing or blank duration");
        }

        String normalizedDuration = durationString.trim();
        final double seconds;
        try {
            seconds = Double.parseDouble(normalizedDuration);
        } catch (NumberFormatException e) {
            throw new VideoMetadataExtractionException(
                    "ffprobe returned an invalid duration value: " + normalizedDuration,
                    e);
        }

        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new VideoMetadataExtractionException(
                    "ffprobe returned an invalid duration value: " + normalizedDuration);
        }

        return Duration.ofMillis(Math.round(seconds * 1000)).toMillis();
    }

    /**
     * Captured stdout and stderr from an ffprobe invocation.
     */
    private record ProbeOutput(String stdout, String stderr) {
    }

    /**
     * Minimal ffprobe response model for worker-side JSON parsing.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FfprobeResponse(
            List<FfprobeStream> streams,
            FfprobeFormat format) {
    }

    /**
     * Minimal ffprobe stream model for worker-side JSON parsing.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FfprobeStream(
            String codec_type,
            String codec_name,
            Integer width,
            Integer height) {
    }

    /**
     * Minimal ffprobe container model for worker-side JSON parsing.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FfprobeFormat(
            String duration,
            String format_name) {
    }
}
