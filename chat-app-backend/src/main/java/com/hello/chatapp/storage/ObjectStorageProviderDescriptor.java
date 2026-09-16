package com.hello.chatapp.storage;

/**
 * Read-only summary of one provider's resolved configuration.
 */
public record ObjectStorageProviderDescriptor(
        ObjectStorageProviderType type,
        String bucket,
        String region,
        String endpoint,
        boolean pathStyleAccess,
        boolean multipartUploadSupported) {
}
