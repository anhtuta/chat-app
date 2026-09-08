package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingLocalTriggerProperties;
import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.dto.LocalMediaProcessingTriggerRequest;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;

/**
 * Builds a worker job payload from a local HTTP trigger request.
 */
@Singleton
public class LocalMediaProcessingJobFactory {

    private static final List<ProcessingTarget> DEFAULT_TARGETS =
            List.of(ProcessingTarget.METADATA, ProcessingTarget.TRANSCODE);

    private final MediaProcessingLocalTriggerProperties localTriggerProperties;
    private final MediaProcessingStorageProperties storageProperties;

    public LocalMediaProcessingJobFactory(
            MediaProcessingLocalTriggerProperties localTriggerProperties,
            MediaProcessingStorageProperties storageProperties) {
        this.localTriggerProperties = localTriggerProperties;
        this.storageProperties = storageProperties;
    }

    /**
     * Converts a local trigger request into the same job shape the RabbitMQ consumer would deliver.
     *
     * @param request HTTP body describing an existing object-storage video
     * @return job payload for {@link MediaProcessingJobHandler}
     */
    public MediaProcessingJobMessage toJob(LocalMediaProcessingTriggerRequest request) {
        String bucket = blankToNull(request.bucket());
        if (bucket == null) {
            bucket = localTriggerProperties.getDefaultBucket();
        }
        String mimeType = blankToNull(request.requestedMimeType());
        if (mimeType == null) {
            mimeType = "video/mp4";
        }
        List<ProcessingTarget> targets = request.processingTargets();
        if (targets == null || targets.isEmpty()) {
            targets = DEFAULT_TARGETS;
        }
        String jobId = blankToNull(request.jobId());
        if (jobId == null) {
            jobId = UUID.randomUUID().toString();
        }
        Long messageId = request.messageId() == null ? 0L : request.messageId();
        Long mediaId = request.mediaId() == null ? 0L : request.mediaId();
        return new MediaProcessingJobMessage(
                jobId,
                messageId,
                mediaId,
                MediaProcessingMessageType.VIDEO,
                storageProperties.getProvider(),
                bucket,
                request.objectKey(),
                mimeType,
                List.copyOf(targets));
    }

    /**
     * Treats blank strings as omitted optional fields.
     *
     * @param value raw request field
     * @return trimmed value, or {@code null} when missing or blank
     */
    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
