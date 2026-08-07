package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import io.minio.AbortMultipartUploadArgs;
import io.minio.BucketExistsArgs;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioAsyncClient;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Part;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
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
    private final MinioAsyncClient minioAsyncClient;

    public MinioObjectStorageProvider(MediaStorageProperties mediaStorageProperties) {
        this.mediaStorageProperties = mediaStorageProperties;
        MediaStorageProperties.Minio minio = mediaStorageProperties.getMinio();
        this.minioClient = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
        this.minioAsyncClient = MinioAsyncClient.builder()
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
    public String createMultipartUpload(String objectKey) {
        try {
            return minioAsyncClient.createMultipartUpload(CreateMultipartUploadArgs.builder()
                    .bucket(mediaStorageProperties.getMinio().getBucket())
                    .object(objectKey)
                    .build())
                    .join()
                    .result()
                    .uploadId();
        } catch (CompletionException e) {
            throw new IllegalStateException("Failed to create MinIO multipart upload", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create MinIO multipart upload", e);
        }
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
    public void completeMultipartUpload(
            String objectKey,
            String multipartUploadId,
            List<ObjectStorageCompletedPart> parts) {
        try {
            Part[] minioParts = parts.stream()
                    .map(part -> new Part(part.partNumber(), part.etag()))
                    .toArray(Part[]::new);
            minioAsyncClient.completeMultipartUpload(CompleteMultipartUploadArgs.builder()
                    .bucket(mediaStorageProperties.getMinio().getBucket())
                    .object(objectKey)
                    .uploadId(multipartUploadId)
                    .parts(minioParts)
                    .build())
                    .join();
        } catch (CompletionException e) {
            throw new IllegalStateException("Failed to complete MinIO multipart upload", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to complete MinIO multipart upload", e);
        }
    }

    @Override
    public void abortMultipartUpload(String objectKey, String multipartUploadId) {
        try {
            minioAsyncClient.abortMultipartUpload(AbortMultipartUploadArgs.builder()
                    .bucket(mediaStorageProperties.getMinio().getBucket())
                    .object(objectKey)
                    .uploadId(multipartUploadId)
                    .build())
                    .join();
        } catch (CompletionException e) {
            throw new IllegalStateException("Failed to abort MinIO multipart upload", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to abort MinIO multipart upload", e);
        }
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
