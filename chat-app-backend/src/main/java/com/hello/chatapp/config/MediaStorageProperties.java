package com.hello.chatapp.config;

import com.hello.chatapp.storage.ObjectStorageProviderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bind media storage configuration properties from application.yaml.
 */
@ConfigurationProperties(prefix = "chat.media")
@Validated
@Getter
@Setter
public class MediaStorageProperties {

    /**
     * Default storage provider is MinIO. But it can be overridden in application.yaml.
     */
    @NotNull
    private ObjectStorageProviderType provider = ObjectStorageProviderType.MINIO;

    @Min(1)
    private int maxImageCount = 50;

    @Min(1)
    private int retentionDays = 60;

    @Min(1)
    private long multipartThresholdBytes = 5L * 1024 * 1024;

    @Min(1)
    private int uploadSessionTtlMinutes = 15;

    @Valid
    @NotNull
    private MaxSize maxSize = new MaxSize();

    @Valid
    @NotNull
    private Minio minio = new Minio();

    @Valid
    @NotNull
    private S3 s3 = new S3();

    @Getter
    @Setter
    public static class MaxSize {

        @Min(1)
        private long imageBytes = 10L * 1024 * 1024;

        @Min(1)
        private long audioBytes = 50L * 1024 * 1024;

        @Min(1)
        private long videoBytes = 200L * 1024 * 1024;

        @Min(1)
        private long fileBytes = 20L * 1024 * 1024;
    }

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
        private String bucket = "chat-media";

        @NotBlank
        private String region = "us-east-1";

        private boolean pathStyleAccess = true;
    }

    @Getter
    @Setter
    public static class S3 {

        @NotBlank
        private String bucket = "chat-media";

        @NotBlank
        private String region = "ap-southeast-1";

        private String endpoint = "";

        private boolean pathStyleAccess = false;
    }
}
