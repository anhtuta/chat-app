package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties;
import com.hello.mediaprocessing.job.MediaProcessingJobMessage;
import com.hello.mediaprocessing.storage.ObjectStorageDownloadException;
import com.hello.mediaprocessing.storage.ObjectStorageDownloadResult;
import com.hello.mediaprocessing.storage.ObjectStorageDownloader;
import jakarta.inject.Singleton;

import java.nio.file.Path;

/**
 * Downloads a job's source object into the worker temp workspace and returns a local source handle.
 */
@Singleton
public class ObjectStorageMediaProcessingSourceLoader implements MediaProcessingSourceLoader {

    private final ObjectStorageDownloader objectStorageDownloader;
    private final MediaProcessingWorkspaceManager workspaceManager;
    private final MediaProcessingWorkspaceProperties workspaceProperties;

    public ObjectStorageMediaProcessingSourceLoader(
            ObjectStorageDownloader objectStorageDownloader,
            MediaProcessingWorkspaceManager workspaceManager,
            MediaProcessingWorkspaceProperties workspaceProperties) {
        this.objectStorageDownloader = objectStorageDownloader;
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
        Path workspaceDirectory = workspaceManager.createWorkspace(job.jobId());
        Path localSourcePath = workspaceManager.resolveLocalSourcePath(workspaceDirectory, job.objectKey());

        try {
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
