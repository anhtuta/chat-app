package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.model.ObjectStorageUploadResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers uploader registration and provider-based lookup for derived-object uploads.
 */
class ObjectStorageUploaderRegistryTest {

    /**
     * Verifies that the registry resolves the uploader bound to the configured provider type.
     */
    @Test
    void getConfiguredUploader_returnsUploaderForConfiguredProvider() {
        ObjectStorageUploader minioUploader = new StubUploader(ObjectStorageProviderType.MINIO);
        ObjectStorageUploader s3Uploader = new StubUploader(ObjectStorageProviderType.S3);
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.setProvider(ObjectStorageProviderType.S3);

        ObjectStorageUploaderRegistry registry =
                new ObjectStorageUploaderRegistry(List.of(minioUploader, s3Uploader), storageProperties);

        assertThat(registry.getConfiguredUploader()).isSameAs(s3Uploader);
        assertThat(registry.getUploader(ObjectStorageProviderType.MINIO)).isSameAs(minioUploader);
    }

    /**
     * Verifies that startup fails when the configured provider has no registered uploader.
     */
    @Test
    void constructor_missingConfiguredUploader_failsFast() {
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.setProvider(ObjectStorageProviderType.S3);

        assertThatThrownBy(() -> new ObjectStorageUploaderRegistry(
                        List.of(new StubUploader(ObjectStorageProviderType.MINIO)), storageProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3");
    }

    /**
     * Minimal uploader used to exercise registry indexing without touching real storage.
     */
    private static final class StubUploader implements ObjectStorageUploader {

        private final ObjectStorageProviderType providerType;

        private StubUploader(ObjectStorageProviderType providerType) {
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
         * @param sourcePath ignored
         * @param contentType ignored
         * @return never returns in practice
         */
        @Override
        public ObjectStorageUploadResult upload(String bucket, String objectKey, Path sourcePath, String contentType) {
            throw new UnsupportedOperationException("Stub uploader");
        }
    }
}
