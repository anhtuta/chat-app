package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.model.ObjectStorageDownloadResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers downloader registration and provider-based lookup for object-storage routing.
 */
class ObjectStorageDownloaderRegistryTest {

    /**
     * Verifies that the registry resolves the downloader bound to the configured provider type.
     */
    @Test
    void getConfiguredDownloader_returnsDownloaderForConfiguredProvider() {
        ObjectStorageDownloader minioDownloader = new StubDownloader(ObjectStorageProviderType.MINIO);
        ObjectStorageDownloader s3Downloader = new StubDownloader(ObjectStorageProviderType.S3);
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.setProvider(ObjectStorageProviderType.S3);

        ObjectStorageDownloaderRegistry registry =
                new ObjectStorageDownloaderRegistry(List.of(minioDownloader, s3Downloader), storageProperties);

        assertThat(registry.getConfiguredDownloader()).isSameAs(s3Downloader);
        assertThat(registry.getDownloader(ObjectStorageProviderType.MINIO)).isSameAs(minioDownloader);
    }

    /**
     * Verifies that startup fails when the configured provider has no registered downloader.
     */
    @Test
    void constructor_missingConfiguredDownloader_failsFast() {
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.setProvider(ObjectStorageProviderType.S3);

        assertThatThrownBy(() -> new ObjectStorageDownloaderRegistry(
                        List.of(new StubDownloader(ObjectStorageProviderType.MINIO)), storageProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3");
    }

    /**
     * Minimal downloader used to exercise registry indexing without touching real storage.
     */
    private static final class StubDownloader implements ObjectStorageDownloader {

        private final ObjectStorageProviderType providerType;

        private StubDownloader(ObjectStorageProviderType providerType) {
            this.providerType = providerType;
        }

        /**
         * Returns the provider type served by this stub.
         *
         * @return configured provider type
         */
        @Override
        public ObjectStorageProviderType getType() {
            return providerType;
        }

        /**
         * Stub implementation that should never be invoked in registry tests.
         *
         * @param bucket ignored
         * @param objectKey ignored
         * @param targetPath ignored
         * @return never returns in practice
         */
        @Override
        public ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath) {
            throw new UnsupportedOperationException("Stub downloader");
        }
    }
}
