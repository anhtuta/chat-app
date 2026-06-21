package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MinIO-backed provider descriptor for local or self-hosted storage.
 * This is a concrete strategy.
 */
@Component
public class MinioObjectStorageProvider implements ObjectStorageProvider {

    private static final Logger logger = LoggerFactory.getLogger(MinioObjectStorageProvider.class);

    private final MediaStorageProperties mediaStorageProperties;
    private final MinioClient minioClient;

    public MinioObjectStorageProvider(MediaStorageProperties mediaStorageProperties) {
        this.mediaStorageProperties = mediaStorageProperties;
        MediaStorageProperties.Minio minio = mediaStorageProperties.getMinio();
        this.minioClient = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }

    @PostConstruct
    public void ensureBucketExists() {
        try {
            MediaStorageProperties.Minio minio = mediaStorageProperties.getMinio();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minio.getBucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(minio.getBucket())
                        .region(minio.getRegion())
                        .build());
                logger.info("Created MinIO bucket {}", minio.getBucket());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize MinIO bucket", e);
        }
    }

    @Override
    public ObjectStorageProviderDescriptor describe() {
        MediaStorageProperties.Minio minio = mediaStorageProperties.getMinio();
        return new ObjectStorageProviderDescriptor(
                getType(),
                minio.getBucket(),
                minio.getRegion(),
                minio.getEndpoint(),
                minio.isPathStyleAccess(),
                true);
    }

    @Override
    public ObjectStorageProviderType getType() {
        return ObjectStorageProviderType.MINIO;
    }

    @Override
    public String buildUploadUrl(String objectKey) {
        return getPresignedObjectUrl(Http.Method.PUT, objectKey, Map.of());
    }

    @Override
    public String buildMultipartUploadPartUrl(String objectKey, String multipartUploadId, int partNumber) {
        return getPresignedObjectUrl(
                Http.Method.PUT,
                objectKey,
                Map.of(
                        "uploadId", multipartUploadId,
                        "partNumber", String.valueOf(partNumber)));
    }

    @Override
    public String buildReadUrl(String objectKey) {
        return getPresignedObjectUrl(Http.Method.GET, objectKey, Map.of());
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(mediaStorageProperties.getMinio().getBucket())
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return false;
            }
            throw new IllegalStateException("Failed to verify object existence in MinIO", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to verify object existence in MinIO", e);
        }
    }

    private String getPresignedObjectUrl(Http.Method method, String objectKey, Map<String, String> extraQueryParams) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(mediaStorageProperties.getMinio().getBucket())
                    .object(objectKey)
                    .expiry(resolveExpiryMinutes(method), TimeUnit.MINUTES)
                    .extraQueryParams(extraQueryParams)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate MinIO presigned URL", e);
        }
    }

    private int resolveExpiryMinutes(Http.Method method) {
        return method == Http.Method.GET
                ? mediaStorageProperties.getReadUrlTtlMinutes()
                : mediaStorageProperties.getUploadUrlTtlMinutes();
    }
}
