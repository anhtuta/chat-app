package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.model.ObjectStorageDownloadResult;
import com.hello.mediaprocessing.storage.ObjectStorageDownloadException;
import com.hello.mediaprocessing.storage.ObjectStorageDownloader;
import com.hello.mediaprocessing.storage.ObjectStorageDownloaderRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers workspace creation, provider routing, cleanup, and typed failure handling for source loading.
 */
class ObjectStorageMediaProcessingSourceLoaderTest {

    @TempDir
    Path tempDir;

    /**
     * Verifies that a successful source download lands in a temp workspace and cleans up on close.
     */
    @Test
    void load_downloadsIntoWorkspaceAndCleansUpOnClose() throws IOException {
        ObjectStorageMediaProcessingSourceLoader sourceLoader = createSourceLoader(
                ObjectStorageProviderType.MINIO,
                new SuccessfulDownloader(ObjectStorageProviderType.MINIO));

        LoadedMediaSource source = sourceLoader.load(buildJob("job-success", ObjectStorageProviderType.MINIO));

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
        ObjectStorageMediaProcessingSourceLoader sourceLoader = createSourceLoader(
                ObjectStorageProviderType.MINIO,
                new MissingSourceDownloader(ObjectStorageProviderType.MINIO));

        MediaProcessingSourceLoadException exception = assertThrows(
                MediaProcessingSourceLoadException.class,
                () -> sourceLoader.load(buildJob("job-missing", ObjectStorageProviderType.MINIO)));

        assertEquals(MediaProcessingFailureReason.SOURCE_MISSING, exception.getFailureReason());
        assertTrue(isWorkspaceBaseEmpty());
    }

    /**
     * Verifies that jobs targeting a different provider than the worker configuration are rejected before download.
     */
    @Test
    void load_mismatchedProvider_rejectsBeforeDownload() {
        AtomicReference<String> downloadedBucket = new AtomicReference<>();
        ObjectStorageMediaProcessingSourceLoader sourceLoader = createSourceLoader(
                ObjectStorageProviderType.MINIO,
                new TrackingDownloader(ObjectStorageProviderType.MINIO, downloadedBucket),
                new TrackingDownloader(ObjectStorageProviderType.S3, downloadedBucket));

        MediaProcessingSourceLoadException exception = assertThrows(
                MediaProcessingSourceLoadException.class,
                () -> sourceLoader.load(buildJob("job-mismatch", ObjectStorageProviderType.S3)));

        assertEquals(MediaProcessingFailureReason.STORAGE_PROVIDER_MISMATCH, exception.getFailureReason());
        assertNull(downloadedBucket.get());
        assertTrue(isWorkspaceBaseEmpty());
    }

    /**
     * Verifies that the downloader registered for the configured provider handles matching jobs.
     */
    @Test
    void load_matchingProvider_routesToConfiguredDownloader() throws IOException {
        AtomicReference<ObjectStorageProviderType> routedProvider = new AtomicReference<>();
        ObjectStorageMediaProcessingSourceLoader sourceLoader = createSourceLoader(
                ObjectStorageProviderType.S3,
                new RoutingDownloader(ObjectStorageProviderType.MINIO, routedProvider),
                new RoutingDownloader(ObjectStorageProviderType.S3, routedProvider));

        try (LoadedMediaSource source = sourceLoader.load(buildJob("job-s3", ObjectStorageProviderType.S3))) {
            assertEquals(ObjectStorageProviderType.S3, routedProvider.get());
            assertTrue(Files.exists(source.getLocalFile()));
        }
    }

    /**
     * Builds a source loader wired to the given configured provider and downloader test doubles.
     *
     * @param configuredProvider provider type configured for the worker instance
     * @param downloaders downloader implementations to register
     * @return source loader under test
     */
    private ObjectStorageMediaProcessingSourceLoader createSourceLoader(
            ObjectStorageProviderType configuredProvider,
            ObjectStorageDownloader... downloaders) {
        MediaProcessingWorkspaceProperties workspaceProperties = new MediaProcessingWorkspaceProperties();
        workspaceProperties.setBaseDirectory(tempDir.toString());
        workspaceProperties.setCleanupEnabled(true);
        MediaProcessingWorkspaceManager workspaceManager = new MediaProcessingWorkspaceManager(workspaceProperties);

        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.setProvider(configuredProvider);
        ObjectStorageDownloaderRegistry downloaderRegistry =
                new ObjectStorageDownloaderRegistry(List.of(downloaders), storageProperties);

        return new ObjectStorageMediaProcessingSourceLoader(
                downloaderRegistry,
                workspaceManager,
                workspaceProperties);
    }

    /**
     * Builds a representative video-processing job used across source-loader tests.
     *
     * @param jobId idempotency key to embed in the test payload
     * @param storageProvider storage provider that owns the source object
     * @return processing job payload for the test case
     */
    private MediaProcessingJobMessage buildJob(String jobId, ObjectStorageProviderType storageProvider) {
        return new MediaProcessingJobMessage(
                jobId,
                100L,
                200L,
                MediaProcessingMessageType.VIDEO,
                storageProvider,
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

        private final ObjectStorageProviderType providerType;

        private SuccessfulDownloader(ObjectStorageProviderType providerType) {
            this.providerType = providerType;
        }

        /**
         * Returns the provider type served by this test double.
         *
         * @return configured provider type
         */
        @Override
        public ObjectStorageProviderType getType() {
            return providerType;
        }

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

        private final ObjectStorageProviderType providerType;

        private MissingSourceDownloader(ObjectStorageProviderType providerType) {
            this.providerType = providerType;
        }

        /**
         * Returns the provider type served by this test double.
         *
         * @return configured provider type
         */
        @Override
        public ObjectStorageProviderType getType() {
            return providerType;
        }

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

    /**
     * Test double that records the bucket name when a download is attempted.
     */
    private static final class TrackingDownloader implements ObjectStorageDownloader {

        private final ObjectStorageProviderType providerType;
        private final AtomicReference<String> downloadedBucket;

        private TrackingDownloader(
                ObjectStorageProviderType providerType,
                AtomicReference<String> downloadedBucket) {
            this.providerType = providerType;
            this.downloadedBucket = downloadedBucket;
        }

        /**
         * Returns the provider type served by this test double.
         *
         * @return configured provider type
         */
        @Override
        public ObjectStorageProviderType getType() {
            return providerType;
        }

        /**
         * Records the bucket name to prove whether routing reached this downloader.
         *
         * @param bucket source bucket name from the test payload
         * @param objectKey ignored in this test double
         * @param targetPath ignored in this test double
         * @return never returns because the method always throws
         */
        @Override
        public ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath) {
            downloadedBucket.set(bucket);
            return new ObjectStorageDownloadResult(1L, "video/mp4", "etag-track");
        }
    }

    /**
     * Test double that records which provider implementation handled the download.
     */
    private static final class RoutingDownloader implements ObjectStorageDownloader {

        private final ObjectStorageProviderType providerType;
        private final AtomicReference<ObjectStorageProviderType> routedProvider;

        private RoutingDownloader(
                ObjectStorageProviderType providerType,
                AtomicReference<ObjectStorageProviderType> routedProvider) {
            this.providerType = providerType;
            this.routedProvider = routedProvider;
        }

        /**
         * Returns the provider type served by this test double.
         *
         * @return configured provider type
         */
        @Override
        public ObjectStorageProviderType getType() {
            return providerType;
        }

        /**
         * Records the routed provider and writes a small local file.
         *
         * @param bucket unused in the test double
         * @param objectKey unused in the test double
         * @param targetPath destination path to receive the fake file
         * @return synthetic download metadata for assertions
         */
        @Override
        public ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath) {
            routedProvider.set(providerType);
            try {
                Files.writeString(targetPath, "routed-video");
                return new ObjectStorageDownloadResult(12L, "video/mp4", "etag-route");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write routed downloaded file", e);
            }
        }
    }
}
