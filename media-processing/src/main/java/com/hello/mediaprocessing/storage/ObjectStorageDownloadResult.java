package com.hello.mediaprocessing.storage;

public record ObjectStorageDownloadResult(
        long objectSize,
        String contentType,
        String etag) {
}
