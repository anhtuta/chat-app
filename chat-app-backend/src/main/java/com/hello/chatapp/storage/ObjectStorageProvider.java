package com.hello.chatapp.storage;

/**
 * Strategy interface for one object-storage provider implementation.
 */
public interface ObjectStorageProvider {

    ObjectStorageProviderDescriptor describe();

    ObjectStorageProviderType getType();

    String buildUploadUrl(String objectKey);

    String createMultipartUpload(String objectKey);

    String buildMultipartUploadPartUrl(String objectKey, String multipartUploadId, int partNumber);

    void completeMultipartUpload(String objectKey, String multipartUploadId, java.util.List<ObjectStorageCompletedPart> parts);

    void abortMultipartUpload(String objectKey, String multipartUploadId);

    String buildReadUrl(String objectKey);

    boolean objectExists(String objectKey);
}
