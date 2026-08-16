package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.model.ObjectStorageDownloadResult;
import java.nio.file.Path;

/**
 * Downloads media source objects from the configured storage backend into the local worker filesystem.
 */
public interface ObjectStorageDownloader {

    /**
     * Returns the storage provider type implemented by this downloader.
     *
     * @return provider type served by this implementation
     */
    ObjectStorageProviderType getType();

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
