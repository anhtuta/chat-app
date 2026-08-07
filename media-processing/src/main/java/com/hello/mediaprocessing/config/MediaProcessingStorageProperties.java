package com.hello.mediaprocessing.config;

import com.hello.mediaprocessing.storage.ObjectStorageProviderType;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds object-storage settings used when workers download media source files.
 */
@ConfigurationProperties("media-processing.storage")
@Introspected
@Getter
@Setter
public class MediaProcessingStorageProperties {

    @NotNull
    private ObjectStorageProviderType provider = ObjectStorageProviderType.MINIO;

    @Valid
    @NotNull
    private Minio minio = new Minio();

    /**
     * Holds the service credentials and connection details for direct MinIO access.
     */
    @Introspected
    @Getter
    @Setter
    public static class Minio {
        @NotBlank
        private String endpoint = "http://localhost:9000";

        @NotBlank
        private String accessKey = "minioadmin";

        @NotBlank
        private String secretKey = "minioadmin";

        @NotBlank
        private String region = "us-east-1";

        private boolean pathStyleAccess = true;
    }
}
