package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.service.MediaProcessingFailureReason;
import io.micronaut.context.annotation.Requires;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Singleton
@Requires(property = "media-processing.storage.provider", value = "MINIO", defaultValue = "MINIO")
public class MinioObjectStorageDownloader implements ObjectStorageDownloader {

    private final MinioClient minioClient;

    public MinioObjectStorageDownloader(MediaProcessingStorageProperties storageProperties) {
        MediaProcessingStorageProperties.Minio minio = storageProperties.getMinio();
        this.minioClient = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }

    @Override
    public ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            if (stat.size() <= 0) {
                // TODO: Replace the zero-byte corruption heuristic with decoder-level validation once Phase 4 metadata extraction is in place.
                throw new ObjectStorageDownloadException(
                        MediaProcessingFailureReason.SOURCE_CORRUPTED,
                        "Downloaded source object is empty: " + bucket + "/" + objectKey);
            }

            try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build())) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            return new ObjectStorageDownloadResult(stat.size(), stat.contentType(), stat.etag());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                throw new ObjectStorageDownloadException(
                        MediaProcessingFailureReason.SOURCE_MISSING,
                        "Source object was not found: " + bucket + "/" + objectKey,
                        e);
            }
            throw new ObjectStorageDownloadException(
                    MediaProcessingFailureReason.SOURCE_UNREADABLE,
                    "Failed to read source object from MinIO: " + bucket + "/" + objectKey,
                    e);
        } catch (IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new ObjectStorageDownloadException(
                    MediaProcessingFailureReason.SOURCE_UNREADABLE,
                    "Failed to download source object from MinIO: " + bucket + "/" + objectKey,
                    e);
        } catch (Exception e) {
            throw new ObjectStorageDownloadException(
                    MediaProcessingFailureReason.SOURCE_UNREADABLE,
                    "Unexpected MinIO download failure for " + bucket + "/" + objectKey,
                    e);
        }
    }
}
