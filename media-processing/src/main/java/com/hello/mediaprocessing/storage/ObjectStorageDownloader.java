package com.hello.mediaprocessing.storage;

import java.nio.file.Path;

public interface ObjectStorageDownloader {

    ObjectStorageDownloadResult download(String bucket, String objectKey, Path targetPath);
}
