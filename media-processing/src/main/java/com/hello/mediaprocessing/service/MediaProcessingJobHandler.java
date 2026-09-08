package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingWorkerProperties;
import com.hello.mediaprocessing.constant.MediaProcessingFailureReason;
import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.constant.VideoTranscodeMode;
import com.hello.mediaprocessing.exception.MediaProcessingSourceLoadException;
import com.hello.mediaprocessing.exception.VideoMetadataExtractionException;
import com.hello.mediaprocessing.exception.VideoPosterGenerationException;
import com.hello.mediaprocessing.exception.VideoTranscodeException;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import com.hello.mediaprocessing.model.ObjectStorageUploadResult;
import com.hello.mediaprocessing.model.VideoMetadata;
import com.hello.mediaprocessing.model.VideoTranscodeResult;
import com.hello.mediaprocessing.storage.ObjectStorageUploadException;
import com.hello.mediaprocessing.storage.ObjectStorageUploaderRegistry;
import com.hello.mediaprocessing.util.VideoPosterObjectKeys;
import com.hello.mediaprocessing.util.VideoTranscodeObjectKeys;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates, deduplicates, and advances processing jobs through metadata extraction, poster generation, and video transcode.
 */
@Singleton
public class MediaProcessingJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(MediaProcessingJobHandler.class);

    private final MediaProcessingWorkerProperties workerProperties;
    private final MediaProcessingJobDeduplicationStore deduplicationStore;
    private final MediaProcessingSourceLoader sourceLoader;
    private final VideoMetadataExtractor videoMetadataExtractor;
    private final VideoPosterGenerator videoPosterGenerator;
    private final VideoTranscoder videoTranscoder;
    private final ObjectStorageUploaderRegistry uploaderRegistry;
    private final MediaProcessingResultSink resultSink;
    private final Validator validator;

    public MediaProcessingJobHandler(
            MediaProcessingWorkerProperties workerProperties,
            MediaProcessingJobDeduplicationStore deduplicationStore,
            MediaProcessingSourceLoader sourceLoader,
            VideoMetadataExtractor videoMetadataExtractor,
            VideoPosterGenerator videoPosterGenerator,
            VideoTranscoder videoTranscoder,
            ObjectStorageUploaderRegistry uploaderRegistry,
            MediaProcessingResultSink resultSink,
            Validator validator) {
        this.workerProperties = workerProperties;
        this.deduplicationStore = deduplicationStore;
        this.sourceLoader = sourceLoader;
        this.videoMetadataExtractor = videoMetadataExtractor;
        this.videoPosterGenerator = videoPosterGenerator;
        this.videoTranscoder = videoTranscoder;
        this.uploaderRegistry = uploaderRegistry;
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

        Set<ProcessingTarget> enabledTargets = resolveEnabledTargets(job);
        logTransition(MediaProcessingJobStatus.VALIDATED, job, "enabledTargets=" + enabledTargets);

        if (enabledTargets.isEmpty()) {
            logTransition(MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS, job, "no enabled targets");
            return MediaProcessingJobStatus.DEFERRED_NO_ENABLED_TARGETS;
        }

        if (!deduplicationStore.tryBeginProcessing(job.jobId())) {
            logTransition(MediaProcessingJobStatus.SKIPPED_DUPLICATE, job, "duplicate job id");
            return MediaProcessingJobStatus.SKIPPED_DUPLICATE;
        }

        boolean terminalSuccess = false;
        try {
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
                            pendingTargets,
                            job.objectKey(),
                            null,
                            null,
                            null,
                            false));
                    return MediaProcessingJobStatus.PROCESSING_IN_PROGRESS;
                }

                logTransition(
                        MediaProcessingJobStatus.DISPATCHED,
                        job,
                        "handoff=" + workerProperties.getHandoff() + ", localSource=" + source.getLocalFile() + ", contentType=" +
                                source.getContentType() + ", bytes=" + source.getObjectSize());

                Set<ProcessingTarget> completedTargets = EnumSet.noneOf(ProcessingTarget.class);
                VideoMetadata videoMetadata = null;
                String thumbnailObjectKey = null;
                String transcodedObjectKey = null;
                Long canonicalObjectSize = null;
                boolean reusedOriginalObject = false;

                if (actionableTargets.contains(ProcessingTarget.METADATA)
                        || actionableTargets.contains(ProcessingTarget.THUMBNAIL)
                        || actionableTargets.contains(ProcessingTarget.TRANSCODE)) {
                    logTransition(
                            MediaProcessingJobStatus.PROCESSING_IN_PROGRESS,
                            job,
                            "actionableTargets=" + actionableTargets);
                    videoMetadata = extractVideoMetadata(job, source);
                }

                if (actionableTargets.contains(ProcessingTarget.METADATA)) {
                    completedTargets.add(ProcessingTarget.METADATA);
                }

                if (actionableTargets.contains(ProcessingTarget.THUMBNAIL)) {
                    Path posterFile = videoPosterGenerator.generate(
                            source.getLocalFile(),
                            source.getWorkspaceDirectory().resolve("poster.thumbnail.jpg"),
                            videoMetadata);
                    verifyPosterFile(posterFile);
                    thumbnailObjectKey = VideoPosterObjectKeys.derive(job.objectKey());
                    try {
                        uploaderRegistry.getUploader(job.storageProvider())
                                .upload(job.bucket(), thumbnailObjectKey, posterFile, "image/jpeg");
                    } catch (ObjectStorageUploadException e) {
                        throw new VideoPosterGenerationException(
                                MediaProcessingFailureReason.POSTER_UPLOAD_FAILED,
                                "Failed to upload poster thumbnail for " + job.objectKey(),
                                e);
                    }
                    completedTargets.add(ProcessingTarget.THUMBNAIL);
                }

                if (actionableTargets.contains(ProcessingTarget.TRANSCODE)) {
                    VideoTranscodeResult transcodeResult = videoTranscoder.transcode(
                            source.getLocalFile(),
                            source.getWorkspaceDirectory().resolve("playback.mp4"),
                            videoMetadata);
                    reusedOriginalObject = transcodeResult.mode() == VideoTranscodeMode.REUSE_ORIGINAL;
                    if (reusedOriginalObject) {
                        transcodedObjectKey = job.objectKey();
                        canonicalObjectSize = source.getObjectSize();
                    } else {
                        verifyTranscodedFile(transcodeResult.outputFile());
                        transcodedObjectKey = VideoTranscodeObjectKeys.derive(job.objectKey());
                        ObjectStorageUploadResult uploadResult = uploaderRegistry
                                .getUploader(job.storageProvider())
                                .upload(job.bucket(), transcodedObjectKey, transcodeResult.outputFile(), "video/mp4");
                        canonicalObjectSize = uploadResult.objectSize();
                    }
                    completedTargets.add(ProcessingTarget.TRANSCODE);
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
                        Set.copyOf(pendingTargets),
                        job.objectKey(),
                        thumbnailObjectKey,
                        transcodedObjectKey,
                        canonicalObjectSize,
                        reusedOriginalObject));
                logTransition(
                        finalStatus,
                        job,
                        "completedTargets=" + completedTargets + "; pendingTargets=" + pendingTargets +
                                (videoMetadata == null ? "" : "; mimeType=" + videoMetadata.detectedMimeType()) +
                                (thumbnailObjectKey == null ? "" : "; thumbnailObjectKey=" + thumbnailObjectKey) +
                                (transcodedObjectKey == null ? "" : "; transcodedObjectKey=" + transcodedObjectKey));
                if (finalStatus == MediaProcessingJobStatus.MEDIA_READY) {
                    deduplicationStore.markCompleted(job.jobId());
                    terminalSuccess = true;
                }
                return finalStatus;
            }
        } catch (MediaProcessingSourceLoadException
                | VideoMetadataExtractionException
                | VideoPosterGenerationException
                | VideoTranscodeException
                | ObjectStorageUploadException e) {
            logTransition(
                    MediaProcessingJobStatus.PROCESSING_FAILED,
                    job,
                    "failureReason=" + resolveFailureReason(e) + ", message=" + e.getMessage());
            resultSink.accept(new MediaProcessingResult(
                    job.jobId(),
                    job.messageId(),
                    job.mediaId(),
                    MediaProcessingJobStatus.PROCESSING_FAILED,
                    null,
                    Set.of(),
                    Set.copyOf(resolveEnabledTargets(job)),
                    job.objectKey(),
                    null,
                    null,
                    null,
                    false));
            return MediaProcessingJobStatus.PROCESSING_FAILED;
        } finally {
            if (!terminalSuccess) {
                deduplicationStore.releaseProcessing(job.jobId());
            }
        }
    }

    /**
     * Returns the processing targets currently implemented by this worker phase.
     *
     * @return targets that can be completed during the current handler execution
     */
    private Set<ProcessingTarget> resolveImplementedTargets() {
        return EnumSet.of(ProcessingTarget.METADATA, ProcessingTarget.THUMBNAIL, ProcessingTarget.TRANSCODE);
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
     * Probes a derived playback file so empty or corrupt ffmpeg output is not uploaded.
     *
     * @param transcodedFile local playback MP4 produced by ffmpeg
     */
    private void verifyTranscodedFile(Path transcodedFile) {
        try {
            videoMetadataExtractor.extract(transcodedFile, "video/mp4");
        } catch (VideoMetadataExtractionException e) {
            throw new VideoTranscodeException(
                    MediaProcessingFailureReason.TRANSCODE_FAILED,
                    "Transcoded playback file failed verification: " + transcodedFile,
                    e);
        }
    }

    /**
     * Verifies that a generated poster exists and is non-empty before the worker uploads it.
     *
     * @param posterFile local poster file produced by ffmpeg
     */
    private void verifyPosterFile(Path posterFile) {
        try {
            if (!Files.isRegularFile(posterFile) || Files.size(posterFile) <= 0) {
                throw new VideoPosterGenerationException(
                        MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                        "Generated poster file is empty or missing: " + posterFile);
            }
        } catch (VideoPosterGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new VideoPosterGenerationException(
                    MediaProcessingFailureReason.POSTER_GENERATION_FAILED,
                    "Failed to verify generated poster file " + posterFile,
                    e);
        }
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
        if (exception instanceof VideoTranscodeException transcodeException) {
            return transcodeException.getFailureReason().name();
        }
        if (exception instanceof VideoPosterGenerationException posterGenerationException) {
            return posterGenerationException.getFailureReason().name();
        }
        if (exception instanceof ObjectStorageUploadException uploadException) {
            return uploadException.getFailureReason().name();
        }
        return MediaProcessingFailureReason.METADATA_EXTRACTION_FAILED.name();
    }
}
