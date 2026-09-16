package com.hello.mediaprocessing.dto;

import com.hello.mediaprocessing.constant.ProcessingTarget;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Local-only request body for triggering a processing job against an existing object-storage file.
 *
 * @param objectKey source object key already present in MinIO/S3
 * @param bucket bucket containing the object; the configured default is used when omitted
 * @param requestedMimeType MIME type to treat as the upload type; defaults to {@code video/mp4}
 * @param processingTargets outputs to run; defaults to metadata plus transcode
 * @param jobId optional idempotency key; a random id is generated when omitted
 * @param messageId optional fake chat message id for the worker payload
 * @param mediaId optional fake media row id for the worker payload
 */
@Serdeable
public record LocalMediaProcessingTriggerRequest(
        @NotBlank String objectKey,
        String bucket,
        String requestedMimeType,
        List<ProcessingTarget> processingTargets,
        String jobId,
        Long messageId,
        Long mediaId) {
}
