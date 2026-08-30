package com.hello.mediaprocessing.model;

import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.constant.ProcessingTarget;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Carries the minimal queue payload needed for a worker to locate and process a media object.
 *
 * @param jobId idempotency key for the processing attempt
 * @param messageId parent chat message identifier
 * @param mediaId media row identifier tied to the uploaded object
 * @param messageType high-level media category
 * @param storageProvider storage backend that owns the object
 * @param bucket bucket or container containing the object
 * @param objectKey provider-specific object key
 * @param requestedMimeType MIME type captured at upload time
 * @param processingTargets outputs requested for the job
 */
@Serdeable
public record MediaProcessingJobMessage(
        @NotBlank String jobId,
        @NotNull Long messageId,
        @NotNull Long mediaId,
        @NotNull MediaProcessingMessageType messageType,
        @NotNull ObjectStorageProviderType storageProvider,
        @NotBlank String bucket,
        @NotBlank String objectKey,
        @NotBlank String requestedMimeType,
        @NotEmpty List<@NotNull ProcessingTarget> processingTargets) {
}
