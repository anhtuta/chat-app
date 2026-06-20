package com.hello.chatapp.storage;

import com.hello.chatapp.config.MediaStorageProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO-backed provider descriptor for local or self-hosted storage.
 * This is a concrete strategy.
 */
@Component
public class MinioObjectStorageProvider implements ObjectStorageProvider {

    private final MediaStorageProperties mediaStorageProperties;

    public MinioObjectStorageProvider(MediaStorageProperties mediaStorageProperties) {
        this.mediaStorageProperties = mediaStorageProperties;
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
}
