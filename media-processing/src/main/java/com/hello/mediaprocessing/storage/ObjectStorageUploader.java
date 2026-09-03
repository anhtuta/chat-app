package com.hello.mediaprocessing.storage;

import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.model.ObjectStorageUploadResult;
import java.nio.file.Path;

/**
 * Uploads derived media objects from the worker filesystem to the configured storage backend.
 */
public interface ObjectStorageUploader {

    /**
     * Returns the storage provider type implemented by this uploader.
     *
     * @return provider type served by this implementation
     */
    ObjectStorageProviderType getType();

    /**
     * Uploads a local file to the given bucket and object key.
     *
     * @param bucket destination bucket
     * @param objectKey provider-specific key for the derived object
     * @param sourcePath local file to upload
     * @param contentType content type to store with the object
     * @return metadata captured during the upload
     */
    ObjectStorageUploadResult upload(String bucket, String objectKey, Path sourcePath, String contentType);
}
