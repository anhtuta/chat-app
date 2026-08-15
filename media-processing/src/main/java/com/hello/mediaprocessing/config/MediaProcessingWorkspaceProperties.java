package com.hello.mediaprocessing.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Binds the temporary workspace location and cleanup behavior for local media files.
 */
@ConfigurationProperties("media-processing.workspace")
@Introspected
@Getter
@Setter
public class MediaProcessingWorkspaceProperties {

    @NotBlank
    private String baseDirectory = System.getProperty("java.io.tmpdir") + "/media-processing";

    private boolean cleanupEnabled = true;
}
