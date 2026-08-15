package com.hello.chatapp.storage;

/**
 * Provider-neutral completed multipart part metadata.
 */
public record ObjectStorageCompletedPart(int partNumber, String etag) {
}
