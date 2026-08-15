package com.hello.mediaprocessing.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.mediaprocessing.config.MediaProcessingVideoMetadataProperties;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Uses ffprobe to extract metadata from downloaded local video files.
 */
@Singleton
public class FfprobeVideoMetadataExtractor implements VideoMetadataExtractor {

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

            boolean completed = process.waitFor(videoMetadataProperties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                ProbeOutput output = awaitReaders(stdoutFuture, stderrFuture);
                throw new VideoMetadataExtractionException(
                        "ffprobe timed out for file " + localFile + formatProbeDiagnostics(output));
            }

            ProbeOutput output = awaitReaders(stdoutFuture, stderrFuture);
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
     * @return captured stdout and stderr from the probe process
     */
    private ProbeOutput awaitReaders(CompletableFuture<String> stdoutFuture, CompletableFuture<String> stderrFuture) {
        try {
            return new ProbeOutput(stdoutFuture.join(), stderrFuture.join());
        } catch (CompletionException e) {
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
     */
    private long parseDurationMillis(String durationString) {
        if (durationString == null || durationString.isBlank()) {
            return 0L;
        }
        double seconds = Double.parseDouble(durationString);
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
