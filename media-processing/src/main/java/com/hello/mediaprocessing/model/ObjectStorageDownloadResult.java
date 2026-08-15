package com.hello.mediaprocessing.model;

/**
 * Reports the basic metadata observed while copying a source object to local disk.
 *
 * @param objectSize size of the downloaded object in bytes
 * @param contentType content type reported by the storage provider
 * @param etag storage-provider ETag for the object version that was downloaded
 */
public record ObjectStorageDownloadResult(
        long objectSize,
        String contentType,
        String etag) {
}
