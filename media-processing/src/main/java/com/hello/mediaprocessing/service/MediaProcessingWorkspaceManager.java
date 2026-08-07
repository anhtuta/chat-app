package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkspaceProperties;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Singleton
public class MediaProcessingWorkspaceManager {

    private static final Logger logger = LoggerFactory.getLogger(MediaProcessingWorkspaceManager.class);

    private final MediaProcessingWorkspaceProperties workspaceProperties;

    public MediaProcessingWorkspaceManager(MediaProcessingWorkspaceProperties workspaceProperties) {
        this.workspaceProperties = workspaceProperties;
    }

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

    public Path resolveLocalSourcePath(Path workspaceDirectory, String objectKey) {
        String fileName = objectKey == null ? "source.bin" : Path.of(objectKey).getFileName().toString();
        if (fileName.isBlank()) {
            fileName = "source.bin";
        }
        return workspaceDirectory.resolve(fileName);
    }

    public void cleanupWorkspaceQuietly(Path workspaceDirectory) {
        try {
            cleanupWorkspace(workspaceDirectory);
        } catch (IOException | RuntimeException e) {
            logger.warn("Failed to clean workspace {}", workspaceDirectory, e);
        }
    }

    private void cleanupWorkspace(Path workspaceDirectory) throws IOException {
        if (workspaceDirectory == null || Files.notExists(workspaceDirectory)) {
            return;
        }
        try (var paths = Files.walk(workspaceDirectory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> deleteQuietly(path));
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete temp path " + path, e);
        }
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9-_]", "_");
    }
}
