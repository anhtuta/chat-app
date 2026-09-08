package com.hello.chatapp.model;

import com.hello.chatapp.constant.MessageType;
import com.hello.chatapp.constant.ProcessingTarget;
import com.hello.chatapp.storage.ObjectStorageProviderType;
import java.util.List;

/**
 * RabbitMQ contract used to ask media-processing-service to process one attachment.
 *
 * @param jobId stable idempotency key for the media row
 * @param messageId parent message identifier
 * @param mediaId attachment identifier
 * @param messageType media category
 * @param storageProvider object-storage provider
 * @param bucket source bucket
 * @param objectKey source object key
 * @param requestedMimeType MIME type captured during upload
 * @param processingTargets outputs requested from the worker
 */
public record MediaProcessingJobMessage(
        String jobId,
        Long messageId,
        Long mediaId,
        MessageType messageType,
        ObjectStorageProviderType storageProvider,
        String bucket,
        String objectKey,
        String requestedMimeType,
        List<ProcessingTarget> processingTargets) {
}
