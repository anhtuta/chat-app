package com.hello.mediaprocessing.service;

import java.nio.file.Path;

/**
 * Wraps a locally downloaded source file together with the workspace that owns it.
 */
public class LoadedMediaSource implements AutoCloseable {

    private final Path workspaceDirectory;
    private final Path localFile;
    private final long objectSize;
    private final String contentType;
    private final MediaProcessingWorkspaceManager workspaceManager;
    private final boolean cleanupEnabled;

    public LoadedMediaSource(
            Path workspaceDirectory,
            Path localFile,
            long objectSize,
            String contentType,
            MediaProcessingWorkspaceManager workspaceManager,
            boolean cleanupEnabled) {
        this.workspaceDirectory = workspaceDirectory;
        this.localFile = localFile;
        this.objectSize = objectSize;
        this.contentType = contentType;
        this.workspaceManager = workspaceManager;
        this.cleanupEnabled = cleanupEnabled;
    }

    public Path getWorkspaceDirectory() {
        return workspaceDirectory;
    }

    public Path getLocalFile() {
        return localFile;
    }

    public long getObjectSize() {
        return objectSize;
    }

    public String getContentType() {
        return contentType;
    }

    /**
     * Cleans the owning workspace when automatic cleanup is enabled for the current worker run.
     */
    @Override
    public void close() {
        if (cleanupEnabled) {
            workspaceManager.cleanupWorkspaceQuietly(workspaceDirectory);
        }
    }
}
