package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers MinIO endpoint scheme checks before the upload client is constructed.
 */
class MinioObjectStorageUploaderTest {

    /**
     * Verifies HTTPS endpoints are accepted without local-development mode.
     */
    @Test
    void constructor_httpsEndpoint_isAcceptedWithoutLocalDevelopment() {
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.getMinio().setEndpoint("https://minio.example.com");

        assertThatCode(() -> new MinioObjectStorageUploader(storageProperties)).doesNotThrowAnyException();
    }

    /**
     * Verifies the localhost HTTP default is still allowed when local development is enabled.
     */
    @Test
    void constructor_localhostHttp_isAcceptedWhenLocalDevelopmentEnabled() {
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.setLocalDevelopment(true);

        assertThat(storageProperties.getMinio().getEndpoint()).isEqualTo("http://localhost:9000");
        assertThatCode(() -> new MinioObjectStorageUploader(storageProperties)).doesNotThrowAnyException();
    }

    /**
     * Verifies HTTP endpoints are rejected in production configuration before the client is built.
     */
    @Test
    void constructor_httpEndpointWithoutLocalDevelopment_failsFast() {
        MediaProcessingStorageProperties storageProperties = new MediaProcessingStorageProperties();
        storageProperties.getMinio().setEndpoint("http://minio.example.com");
        storageProperties.getMinio().setAccessKey("should-not-be-used");
        storageProperties.getMinio().setSecretKey("should-not-be-used");

        assertThatThrownBy(() -> new MinioObjectStorageUploader(storageProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS")
                .hasMessageContaining("http://minio.example.com");
    }
}
