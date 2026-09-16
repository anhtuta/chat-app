package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.exception.MediaProcessingSourceLoadException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Creates and removes the temporary directories used while a worker is handling a media file.
 */
@Singleton
public class MediaProcessingWorkspaceManager {

    private static final Logger logger = LoggerFactory.getLogger(MediaProcessingWorkspaceManager.class);

    private final MediaProcessingWorkspaceProperties workspaceProperties;

    public MediaProcessingWorkspaceManager(MediaProcessingWorkspaceProperties workspaceProperties) {
        this.workspaceProperties = workspaceProperties;
    }

    /**
     * Creates a new isolated workspace directory for a single job attempt.
     *
     * @param jobId job identifier used to build a readable workspace prefix
     * @return path to the created workspace directory
     */
    public Path createWorkspace(String jobId) {
        try {
            Path baseDirectory = Path.of(workspaceProperties.getBaseDirectory());
            Files.createDirectories(baseDirectory);
            return Files.createTempDirectory(baseDirectory, sanitize(jobId) + "-");
        } catch (IOException e) {
            throw new MediaProcessingSourceLoadException(
                    MediaProcessingFailureReason.TEMP_FILE_PREPARATION_FAILED,
                    "Failed to create workspace for job " + jobId,
                    e);
        }
    }

    /**
     * Resolves the local destination path for a downloaded source object inside a workspace.
     *
     * @param workspaceDirectory workspace allocated for the job
     * @param objectKey provider-specific object key
     * @return local file path that should receive the object contents
     */
    public Path resolveLocalSourcePath(Path workspaceDirectory, String objectKey) {
        String fileName = objectKey == null ? "source.bin" : Path.of(objectKey).getFileName().toString();
        if (fileName.isBlank()) {
            fileName = "source.bin";
        }
        return workspaceDirectory.resolve(fileName);
    }

    /**
     * Attempts to remove a workspace and logs any cleanup failure without interrupting the worker flow.
     *
     * @param workspaceDirectory workspace directory to remove
     */
    public void cleanupWorkspaceQuietly(Path workspaceDirectory) {
        try {
            cleanupWorkspace(workspaceDirectory);
        } catch (IOException | RuntimeException e) {
            logger.warn("Failed to clean workspace {}", workspaceDirectory, e);
        }
    }

    /**
     * Recursively deletes a workspace directory and all files contained in it.
     *
     * @param workspaceDirectory workspace directory to delete
     * @throws IOException when filesystem traversal or deletion fails
     */
    private void cleanupWorkspace(Path workspaceDirectory) throws IOException {
        if (workspaceDirectory == null || Files.notExists(workspaceDirectory)) {
            return;
        }
        List<IOException> deletionFailures = new ArrayList<>();
        try (var paths = Files.walk(workspaceDirectory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> attemptDelete(path, deletionFailures));
        }
        if (!deletionFailures.isEmpty()) {
            IOException aggregate = new IOException("Failed to delete workspace " + workspaceDirectory);
            deletionFailures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }
    }

    /**
     * Attempts to delete a single file-system path and records any failure without aborting traversal.
     *
     * @param path file or directory to delete
     * @param deletionFailures collector for per-path deletion errors
     */
    private void attemptDelete(Path path, List<IOException> deletionFailures) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            deletionFailures.add(e);
        }
    }

    /**
     * Converts a job id into a filesystem-safe directory prefix.
     *
     * @param value raw job id
     * @return sanitized value that is safe to embed in directory names
     */
    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9-_]", "_");
    }
}
