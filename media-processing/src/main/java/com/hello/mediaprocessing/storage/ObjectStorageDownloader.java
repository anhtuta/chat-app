package com.hello.mediaprocessing.storage;

import java.nio.file.Path;

/**
 * Downloads media source objects from the configured storage backend into the local worker filesystem.
 */
public interface ObjectStorageDownloader {

    /**
     * Downloads an object into a caller-provided local file path.
     *
     * @param bucket source bucket containing the media object
     * @param objectKey provider-specific key for the object
     * @param targetPath local path that should receive the downloaded bytes
     * @return metadata captured during the download
     */
    ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath);
}
