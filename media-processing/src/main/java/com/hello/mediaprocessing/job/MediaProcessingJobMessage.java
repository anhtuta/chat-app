package com.hello.mediaprocessing.job;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Serdeable
public record MediaProcessingJobMessage(
        @NotBlank String jobId,
        @NotNull Long messageId,
        @NotNull Long mediaId,
        @NotNull MediaProcessingMessageType messageType,
        @NotBlank String storageProvider,
        @NotBlank String bucket,
        @NotBlank String objectKey,
        @NotBlank String requestedMimeType,
        @NotEmpty List<@NotNull ProcessingTarget> processingTargets) {
}
