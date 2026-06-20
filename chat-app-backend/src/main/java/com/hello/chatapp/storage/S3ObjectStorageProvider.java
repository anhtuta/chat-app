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
}
