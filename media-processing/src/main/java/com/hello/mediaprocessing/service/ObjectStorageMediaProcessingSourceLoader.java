package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.exception.MediaProcessingSourceLoadException;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.model.ObjectStorageDownloadResult;
import com.hello.mediaprocessing.storage.ObjectStorageDownloadException;
import com.hello.mediaprocessing.storage.ObjectStorageDownloader;
import com.hello.mediaprocessing.storage.ObjectStorageDownloaderRegistry;
import jakarta.inject.Singleton;
import java.nio.file.Path;

/**
 * Downloads a job's source object into the worker temp workspace and returns a local source handle.
 */
@Singleton
public class ObjectStorageMediaProcessingSourceLoader implements MediaProcessingSourceLoader {

    private final ObjectStorageDownloaderRegistry downloaderRegistry;
    private final MediaProcessingWorkspaceManager workspaceManager;
    private final MediaProcessingWorkspaceProperties workspaceProperties;

    public ObjectStorageMediaProcessingSourceLoader(
            ObjectStorageDownloaderRegistry downloaderRegistry,
            MediaProcessingWorkspaceManager workspaceManager,
            MediaProcessingWorkspaceProperties workspaceProperties) {
        this.downloaderRegistry = downloaderRegistry;
        this.workspaceManager = workspaceManager;
        this.workspaceProperties = workspaceProperties;
    }

    /**
     * Creates a workspace, downloads the remote object into it, and returns a handle that owns cleanup.
     *
     * @param job job describing the source object that should be materialized locally
     * @return handle for the downloaded local file
     */
    @Override
    public LoadedMediaSource load(MediaProcessingJobMessage job) {
        if (job.storageProvider() != downloaderRegistry.getConfiguredProviderType()) {
            throw new MediaProcessingSourceLoadException(
                    MediaProcessingFailureReason.STORAGE_PROVIDER_MISMATCH,
                    "Job storage provider " + job.storageProvider() + " does not match configured provider " +
                            downloaderRegistry.getConfiguredProviderType());
        }

        ObjectStorageDownloader objectStorageDownloader = downloaderRegistry.getDownloader(job.storageProvider());
        Path workspaceDirectory = workspaceManager.createWorkspace(job.jobId());

        try {
            Path localSourcePath = workspaceManager.resolveLocalSourcePath(workspaceDirectory, job.objectKey());
            ObjectStorageDownloadResult downloadResult = objectStorageDownloader.download(
                    job.bucket(),
                    job.objectKey(),
                    localSourcePath);
            String contentType = downloadResult.contentType() == null || downloadResult.contentType().isBlank()
                    ? job.requestedMimeType()
                    : downloadResult.contentType();
            return new LoadedMediaSource(
                    workspaceDirectory,
                    localSourcePath,
                    downloadResult.objectSize(),
                    contentType,
                    workspaceManager,
                    workspaceProperties.isCleanupEnabled());
        } catch (ObjectStorageDownloadException e) {
            workspaceManager.cleanupWorkspaceQuietly(workspaceDirectory);
            throw new MediaProcessingSourceLoadException(e.getFailureReason(), e.getMessage(), e);
        } catch (RuntimeException e) {
            workspaceManager.cleanupWorkspaceQuietly(workspaceDirectory);
            throw e;
        }
    }
}
