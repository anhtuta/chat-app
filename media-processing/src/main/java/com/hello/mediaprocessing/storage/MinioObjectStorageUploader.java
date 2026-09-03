package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.model.ObjectStorageUploadResult;
import io.micronaut.context.annotation.Requires;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import jakarta.inject.Singleton;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uploads derived objects to MinIO using service credentials.
 */
@Singleton
@Requires(property = "media-processing.storage.provider", value = "MINIO", defaultValue = "MINIO")
public class MinioObjectStorageUploader implements ObjectStorageUploader {

    private final MinioClient minioClient;

    public MinioObjectStorageUploader(MediaProcessingStorageProperties storageProperties) {
        MediaProcessingStorageProperties.Minio minio = storageProperties.getMinio();
        MinioClient minioClient = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .region(minio.getRegion())
                .build();
        if (minio.isPathStyleAccess()) {
            minioClient.disableVirtualStyleEndpoint();
        }
        this.minioClient = minioClient;
    }

    /**
     * Returns the MinIO provider type for registry routing.
     *
     * @return {@link ObjectStorageProviderType#MINIO}
     */
    @Override
    public ObjectStorageProviderType getType() {
        return ObjectStorageProviderType.MINIO;
    }

    /**
     * Uploads a local file into MinIO and returns the key that was written.
     *
     * @param bucket destination bucket
     * @param objectKey destination object key
     * @param sourcePath local file to upload
     * @param contentType content type stored with the object
     * @return upload metadata
     */
    @Override
    public ObjectStorageUploadResult upload(String bucket, String objectKey, Path sourcePath, String contentType) {
        try {
            long objectSize = Files.size(sourcePath);
            if (objectSize <= 0) {
                throw new ObjectStorageUploadException(
                        MediaProcessingFailureReason.TRANSCODE_UPLOAD_FAILED,
                        "Refusing to upload empty derived object: " + bucket + "/" + objectKey);
            }
            try (InputStream inputStream = Files.newInputStream(sourcePath)) {
                ObjectWriteResponse response = minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .contentType(contentType)
                        .stream(inputStream, objectSize, -1L)
                        .build());
                if (response == null) {
                    throw new ObjectStorageUploadException(
                            MediaProcessingFailureReason.TRANSCODE_UPLOAD_FAILED,
                            "MinIO returned no response while uploading " + bucket + "/" + objectKey);
                }
            }
            return new ObjectStorageUploadResult(objectKey, objectSize, contentType);
        } catch (ObjectStorageUploadException e) {
            throw e;
        } catch (Exception e) {
            throw new ObjectStorageUploadException(
                    MediaProcessingFailureReason.TRANSCODE_UPLOAD_FAILED,
                    "Failed to upload derived object to MinIO: " + bucket + "/" + objectKey,
                    e);
        }
    }
}
