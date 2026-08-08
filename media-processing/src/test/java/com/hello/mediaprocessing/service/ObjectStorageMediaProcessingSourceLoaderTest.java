package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.model.ObjectStorageDownloadResult;
import com.hello.mediaprocessing.storage.ObjectStorageDownloadException;
import com.hello.mediaprocessing.storage.ObjectStorageDownloader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers workspace creation, cleanup, and typed failure handling for source loading.
 */
class ObjectStorageMediaProcessingSourceLoaderTest {

    @TempDir
    Path tempDir;

    /**
     * Verifies that a successful source download lands in a temp workspace and cleans up on close.
     */
    @Test
    void load_downloadsIntoWorkspaceAndCleansUpOnClose() throws IOException {
        MediaProcessingWorkspaceProperties workspaceProperties = new MediaProcessingWorkspaceProperties();
        workspaceProperties.setBaseDirectory(tempDir.toString());
        workspaceProperties.setCleanupEnabled(true);
        MediaProcessingWorkspaceManager workspaceManager = new MediaProcessingWorkspaceManager(workspaceProperties);
        ObjectStorageMediaProcessingSourceLoader sourceLoader = new ObjectStorageMediaProcessingSourceLoader(
                new SuccessfulDownloader(),
                workspaceManager,
                workspaceProperties);

        LoadedMediaSource source = sourceLoader.load(buildJob("job-success"));

        assertTrue(Files.exists(source.getLocalFile()));
        assertEquals("video/mp4", source.getContentType());
        Path workspaceDirectory = source.getWorkspaceDirectory();

        source.close();

        assertFalse(Files.exists(workspaceDirectory));
    }

    /**
     * Verifies that download failures clean the workspace and preserve the failure reason.
     */
    @Test
    void load_failureCleansWorkspaceAndRaisesTypedException() {
        MediaProcessingWorkspaceProperties workspaceProperties = new MediaProcessingWorkspaceProperties();
        workspaceProperties.setBaseDirectory(tempDir.toString());
        workspaceProperties.setCleanupEnabled(true);
        MediaProcessingWorkspaceManager workspaceManager = new MediaProcessingWorkspaceManager(workspaceProperties);
        ObjectStorageMediaProcessingSourceLoader sourceLoader = new ObjectStorageMediaProcessingSourceLoader(
                new MissingSourceDownloader(),
                workspaceManager,
                workspaceProperties);

        MediaProcessingSourceLoadException exception = assertThrows(
                MediaProcessingSourceLoadException.class,
                () -> sourceLoader.load(buildJob("job-missing")));

        assertEquals(MediaProcessingFailureReason.SOURCE_MISSING, exception.getFailureReason());
        assertTrue(isWorkspaceBaseEmpty());
    }

    /**
     * Builds a representative video-processing job used across source-loader tests.
     *
     * @param jobId idempotency key to embed in the test payload
     * @return processing job payload for the test case
     */
    private MediaProcessingJobMessage buildJob(String jobId) {
        return new MediaProcessingJobMessage(
                jobId,
                100L,
                200L,
                MediaProcessingMessageType.VIDEO,
                "MINIO",
                "chat-media",
                "media/7/video/demo.mp4",
                "video/mp4",
                List.of(ProcessingTarget.METADATA));
    }

    /**
     * Checks whether the temporary workspace base directory is empty after a test action.
     *
     * @return {@code true} when no child paths remain under the temp base directory
     */
    private boolean isWorkspaceBaseEmpty() {
        try (var children = Files.list(tempDir)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect temp workspace directory", e);
        }
    }

    /**
     * Test double that simulates a successful source-object download.
     */
    private static final class SuccessfulDownloader implements ObjectStorageDownloader {

        /**
         * Writes a small local file to mimic a downloaded object.
         *
         * @param bucket unused in the test double
         * @param objectKey unused in the test double
         * @param targetPath destination path to receive the fake file
         * @return synthetic download metadata for assertions
         */
        @Override
        public ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath) {
            try {
                Files.writeString(targetPath, "test-video");
                return new ObjectStorageDownloadResult(10L, "video/mp4", "etag-1");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write fake downloaded file", e);
            }
        }
    }

    /**
     * Test double that always reports a missing source object.
     */
    private static final class MissingSourceDownloader implements ObjectStorageDownloader {

        /**
         * Throws a typed missing-source exception instead of writing a local file.
         *
         * @param bucket source bucket name from the test payload
         * @param objectKey source object key from the test payload
         * @param targetPath ignored because the download fails immediately
         * @return never returns because the method always throws
         */
        @Override
        public ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath) {
            throw new ObjectStorageDownloadException(
                    MediaProcessingFailureReason.SOURCE_MISSING,
                    "Missing source for " + bucket + "/" + objectKey);
        }
    }
}
