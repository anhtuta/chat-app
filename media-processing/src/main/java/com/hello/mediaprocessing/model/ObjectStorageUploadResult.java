package com.hello.mediaprocessing.model;

/**
 * Reports metadata observed after writing a derived object to storage.
 *
 * @param objectKey object key that was written
 * @param objectSize size of the uploaded object in bytes
 * @param contentType content type sent with the upload
 */
public record ObjectStorageUploadResult(String objectKey, long objectSize, String contentType) {
}
