package com.hello.chatapp.storage;

import java.util.Optional;

/**
 * Strategy interface for one object-storage provider implementation.
 */
public interface ObjectStorageProvider {

    ObjectStorageProviderDescriptor describe();

    ObjectStorageProviderType getType();

    String buildUploadUrl(String objectKey);

    String buildMultipartUploadPartUrl(String objectKey, String multipartUploadId, int partNumber);

    String buildReadUrl(String objectKey);

    boolean objectExists(String objectKey);

    /**
     * Returns the provider-stored ETag for an object, or empty when the object does not exist.
     */
    Optional<String> findObjectEtag(String objectKey);

    /**
     * When false, upload completion falls back to existence checks only.
     */
    default boolean supportsStoredEtagVerification() {
        return true;
    }
}
