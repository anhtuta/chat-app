package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import org.springframework.stereotype.Component;

/**
 * S3-backed provider descriptor for AWS-style object storage.
 * This is a concrete strategy.
 */
@Component
public class S3ObjectStorageProvider implements ObjectStorageProvider {

    private final MediaStorageProperties mediaStorageProperties;

    public S3ObjectStorageProvider(MediaStorageProperties mediaStorageProperties) {
        this.mediaStorageProperties = mediaStorageProperties;
    }

    @Override
    public ObjectStorageProviderDescriptor describe() {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        return new ObjectStorageProviderDescriptor(
                getType(),
                s3.getBucket(),
                s3.getRegion(),
                s3.getEndpoint(),
                s3.isPathStyleAccess(),
                true);
    }

    @Override
    public ObjectStorageProviderType getType() {
        return ObjectStorageProviderType.S3;
    }

    @Override
    public String buildUploadUrl(String objectKey) {
        MediaStorageProperties.S3 s3 = mediaStorageProperties.getS3();
        String endpoint = (s3.getEndpoint() == null || s3.getEndpoint().isBlank())
                ? "https://s3." + s3.getRegion() + ".amazonaws.com"
                : s3.getEndpoint();
        if (s3.isPathStyleAccess()) {
            return endpoint + "/" + s3.getBucket() + "/" + objectKey;
        }
        return endpoint + "/" + s3.getBucket() + "/" + objectKey;
    }

    @Override
    public String buildMultipartUploadPartUrl(String objectKey, String multipartUploadId, int partNumber) {
        return buildUploadUrl(objectKey) + "?uploadId=" + multipartUploadId + "&partNumber=" + partNumber;
    }
}
