package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkerProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import com.hello.mediaprocessing.model.VideoMetadata;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates, deduplicates, and advances processing jobs through the initial worker lifecycle.
 */
@Singleton
public class MediaProcessingJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(MediaProcessingJobHandler.class);

    private final MediaProcessingWorkerProperties workerProperties;
    private final MediaProcessingJobDeduplicationStore deduplicationStore;
    private final MediaProcessingSourceLoader sourceLoader;
    private final VideoMetadataExtractor videoMetadataExtractor;
    private final MediaProcessingResultSink resultSink;
    private final Validator validator;

    public MediaProcessingJobHandler(
            MediaProcessingWorkerProperties workerProperties,
            MediaProcessingJobDeduplicationStore deduplicationStore,
            MediaProcessingSourceLoader sourceLoader,
            VideoMetadataExtractor videoMetadataExtractor,
            MediaProcessingResultSink resultSink,
            Validator validator) {
        this.workerProperties = workerProperties;
        this.deduplicationStore = deduplicationStore;
        this.sourceLoader = sourceLoader;
        this.videoMetadataExtractor = videoMetadataExtractor;
        this.resultSink = resultSink;
        this.validator = validator;
    }

    /**
     * Handles a single processing job from validation through source-file acquisition.
     *
     * @param job queue payload describing the media object and requested outputs
     * @return terminal status reached by the worker for this handling attempt
     */
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

        try (LoadedMediaSource source = sourceLoader.load(job)) {
            Set<ProcessingTarget> implementedTargets = resolveImplementedTargets();
            Set<ProcessingTarget> actionableTargets = EnumSet.copyOf(enabledTargets);
            actionableTargets.retainAll(implementedTargets);

            if (actionableTargets.isEmpty()) {
                Set<ProcessingTarget> pendingTargets = Set.copyOf(enabledTargets);
                logTransition(
                        MediaProcessingJobStatus.PROCESSING_IN_PROGRESS,
                        job,
                        "source loaded; pendingTargets=" + pendingTargets + ", awaiting later phases");
                resultSink.accept(new MediaProcessingResult(
                        job.jobId(),
                        job.messageId(),
                        job.mediaId(),
                        MediaProcessingJobStatus.PROCESSING_IN_PROGRESS,
                        null,
                        Set.of(),
                        pendingTargets));
                return MediaProcessingJobStatus.PROCESSING_IN_PROGRESS;
            }

            logTransition(
                    MediaProcessingJobStatus.DISPATCHED,
                    job,
                    "handoff=" + workerProperties.getHandoff() + ", localSource=" + source.getLocalFile() + ", contentType=" +
                            source.getContentType() + ", bytes=" + source.getObjectSize());

            Set<ProcessingTarget> completedTargets = EnumSet.noneOf(ProcessingTarget.class);
            VideoMetadata videoMetadata = null;

            if (actionableTargets.contains(ProcessingTarget.METADATA)) {
                logTransition(
                        MediaProcessingJobStatus.PROCESSING_IN_PROGRESS,
                        job,
                        "actionableTargets=" + actionableTargets);
                videoMetadata = extractVideoMetadata(job, source);
                completedTargets.add(ProcessingTarget.METADATA);
            }

            Set<ProcessingTarget> pendingTargets = EnumSet.copyOf(enabledTargets);
            pendingTargets.removeAll(completedTargets);
            MediaProcessingJobStatus finalStatus = pendingTargets.isEmpty()
                    ? MediaProcessingJobStatus.MEDIA_READY
                    : MediaProcessingJobStatus.PROCESSING_IN_PROGRESS;
            resultSink.accept(new MediaProcessingResult(
                    job.jobId(),
                    job.messageId(),
                    job.mediaId(),
                    finalStatus,
                    videoMetadata,
                    Set.copyOf(completedTargets),
                    Set.copyOf(pendingTargets)));
            logTransition(
                    finalStatus,
                    job,
                    "completedTargets=" + completedTargets + "; pendingTargets=" + pendingTargets +
                            (videoMetadata == null ? "" : "; mimeType=" + videoMetadata.detectedMimeType()));
            return finalStatus;
        } catch (MediaProcessingSourceLoadException | VideoMetadataExtractionException e) {
            logTransition(
                    MediaProcessingJobStatus.PROCESSING_FAILED,
                    job,
                    "failureReason=" + resolveFailureReason(e) + ", message=" + e.getMessage());
            return MediaProcessingJobStatus.PROCESSING_FAILED;
        }
    }

    /**
     * Returns the processing targets currently implemented by this worker phase.
     *
     * @return targets that can be completed during the current handler execution
     */
    private Set<ProcessingTarget> resolveImplementedTargets() {
        return EnumSet.of(ProcessingTarget.METADATA);
    }

    /**
     * Filters requested targets down to the subset currently enabled by worker feature flags.
     *
     * @param job processing job being evaluated
     * @return enabled targets that may proceed in the pipeline
     */
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

    /**
     * Checks whether a specific target is enabled for the current media type and feature-flag set.
     *
     * @param messageType high-level media type for the job
     * @param target requested output to evaluate
     * @param flags worker feature flags controlling partial rollouts
     * @return {@code true} when the target can currently run
     */
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

    /**
     * Logs a structured state transition for observability while the pipeline is still lightweight.
     *
     * @param status status reached by the worker
     * @param job job being processed
     * @param detail additional context for operators and debugging
     */
    private void logTransition(MediaProcessingJobStatus status, MediaProcessingJobMessage job, String detail) {
        logger.info(
                "media-processing jobId={} mediaId={} messageId={} status={} detail={}",
                job.jobId(),
                job.mediaId(),
                job.messageId(),
                status,
                detail);
    }

    /**
     * Extracts video metadata from a loaded local source file.
     *
     * @param job job currently being processed
     * @param source local source file handle for the current job
     * @return normalized video metadata for the source file
     */
    private VideoMetadata extractVideoMetadata(MediaProcessingJobMessage job, LoadedMediaSource source) {
        if (job.messageType() != MediaProcessingMessageType.VIDEO) {
            throw new VideoMetadataExtractionException("Video metadata extraction requires a VIDEO job");
        }
        return videoMetadataExtractor.extract(source.getLocalFile(), source.getContentType());
    }

    /**
     * Maps worker exceptions to the normalized failure reason names used in logs and future result contracts.
     *
     * @param exception failure thrown during processing
     * @return failure-reason name suitable for structured worker logs
     */
    private String resolveFailureReason(Exception exception) {
        if (exception instanceof MediaProcessingSourceLoadException sourceLoadException) {
            return sourceLoadException.getFailureReason().name();
        }
        return MediaProcessingFailureReason.METADATA_EXTRACTION_FAILED.name();
    }
}
