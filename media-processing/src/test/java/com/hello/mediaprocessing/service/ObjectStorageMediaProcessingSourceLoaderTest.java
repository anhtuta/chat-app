package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties;
import com.hello.mediaprocessing.job.MediaProcessingJobMessage;
import com.hello.mediaprocessing.job.MediaProcessingMessageType;
import com.hello.mediaprocessing.job.ProcessingTarget;
import com.hello.mediaprocessing.storage.ObjectStorageDownloadException;
import com.hello.mediaprocessing.storage.ObjectStorageDownloadResult;
import com.hello.mediaprocessing.storage.ObjectStorageDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStorageMediaProcessingSourceLoaderTest {

    @TempDir
    Path tempDir;

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

    private boolean isWorkspaceBaseEmpty() {
        try (var children = Files.list(tempDir)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect temp workspace directory", e);
        }
    }

    private static final class SuccessfulDownloader implements ObjectStorageDownloader {

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

    private static final class MissingSourceDownloader implements ObjectStorageDownloader {

        @Override
        public ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath) {
            throw new ObjectStorageDownloadException(
                    MediaProcessingFailureReason.SOURCE_MISSING,
                    "Missing source for " + bucket + "/" + objectKey);
        }
    }
}
