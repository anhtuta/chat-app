package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkerProperties;
import com.hello.mediaprocessing.job.MediaProcessingJobMessage;
import com.hello.mediaprocessing.job.MediaProcessingJobStatus;
import com.hello.mediaprocessing.job.MediaProcessingMessageType;
import com.hello.mediaprocessing.job.ProcessingTarget;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Singleton
public class MediaProcessingJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(MediaProcessingJobHandler.class);

    private final MediaProcessingWorkerProperties workerProperties;
    private final MediaProcessingJobDeduplicationStore deduplicationStore;
    private final Validator validator;

    public MediaProcessingJobHandler(
            MediaProcessingWorkerProperties workerProperties,
            MediaProcessingJobDeduplicationStore deduplicationStore,
            Validator validator) {
        this.workerProperties = workerProperties;
        this.deduplicationStore = deduplicationStore;
        this.validator = validator;
    }

    public MediaProcessingJobStatus handle(MediaProcessingJobMessage job) {
        logTransition(MediaProcessingJobStatus.RECEIVED, job, "received");

        Set<ConstraintViolation<MediaProcessingJobMessage>> violations = validator.validate(job);
        if (!violations.isEmpty()) {
            logTransition(MediaProcessingJobStatus.REJECTED_INVALID, job, violations.iterator().next().getMessage());
            return MediaProcessingJobStatus.REJECTED_INVALID;
        }

        if (!deduplicationStore.markIfFirstSeen(job.jobId())) {
            logTransition(MediaProcessingJobStatus.SKIPPED_DUPLICATE, job, "duplicate job id");
            return MediaProcessingJobStatus.SKIPPED_DUPLICATE;
        }

        Set<ProcessingTarget> enabledTargets = resolveEnabledTargets(job);
        logTransition(MediaProcessingJobStatus.VALIDATED, job, "enabledTargets=" + enabledTargets);

        if (enabledTargets.isEmpty()) {
            logTransition(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS, job, "no enabled targets");
            return MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS;
        }

        // TODO: Fan out into actual processing executors once Phase 3 object-storage loading is implemented.
        logTransition(MediaProcessingJobStatus.DISPATCHED, job, "handoff=" + workerProperties.getHandoff());
        return MediaProcessingJobStatus.DISPATCHED;
    }

    private Set<ProcessingTarget> resolveEnabledTargets(MediaProcessingJobMessage job) {
        EnumSet<ProcessingTarget> enabledTargets = EnumSet.noneOf(ProcessingTarget.class);
        MediaProcessingWorkerProperties.FeatureFlags flags = workerProperties.getFeatureFlags();
        List<ProcessingTarget> requestedTargets = job.processingTargets();

        for (ProcessingTarget target : requestedTargets) {
            if (isTargetEnabled(job.messageType(), target, flags)) {
                enabledTargets.add(target);
            }
        }
        return enabledTargets;
    }

    private boolean isTargetEnabled(
            MediaProcessingMessageType messageType,
            ProcessingTarget target,
            MediaProcessingWorkerProperties.FeatureFlags flags) {
        return switch (target) {
            case METADATA -> messageType == MediaProcessingMessageType.VIDEO && flags.isVideoMetadata();
            case THUMBNAIL, PREVIEW -> switch (messageType) {
                case VIDEO -> flags.isVideoPoster();
                case IMAGE -> flags.isImageProcessing();
                default -> false;
            };
            case TRANSCODE -> messageType == MediaProcessingMessageType.VIDEO && flags.isVideoTranscode();
            case VIDEO_OCR -> messageType == MediaProcessingMessageType.VIDEO && flags.isVideoOcr();
            case SPEECH_TO_TEXT -> (messageType == MediaProcessingMessageType.VIDEO
                    || messageType == MediaProcessingMessageType.AUDIO) && flags.isSpeechToText();
            case IMAGE_OCR -> messageType == MediaProcessingMessageType.IMAGE && flags.isImageOcr();
        };
    }

    private void logTransition(MediaProcessingJobStatus status, MediaProcessingJobMessage job, String detail) {
        logger.info(
                "media-processing jobId={} mediaId={} messageId={} status={} detail={}",
                job.jobId(),
                job.mediaId(),
                job.messageId(),
                status,
                detail);
    }
}
