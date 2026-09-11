package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.MediaProcessingResult;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs worker results until a later phase introduces a real callback or persistence implementation.
 */
@Singleton
@Requires(property = "media-processing.callback.enabled", value = "false", defaultValue = "false")
public class LoggingMediaProcessingResultSink implements MediaProcessingResultSink {

    private static final Logger logger = LoggerFactory.getLogger(LoggingMediaProcessingResultSink.class);

    private final LastMediaProcessingResultHolder lastResultHolder;

    public LoggingMediaProcessingResultSink(@Nullable LastMediaProcessingResultHolder lastResultHolder) {
        this.lastResultHolder = lastResultHolder;
    }

    /**
     * Logs the normalized worker result so operators can inspect outputs before Phase 7 integration exists.
     *
     * @param result normalized worker output for the processed job
     */
    @Override
    public void accept(MediaProcessingResult result) {
        // TODO is this for local-debug only? Can we remove this?
        if (lastResultHolder != null) {
            lastResultHolder.set(result);
        }
        logger.info(
                "media-processing result jobId={} mediaId={} messageId={} status={} completedTargets={} pendingTargets={} originalObjectKey={} thumbnailObjectKey={} transcodedObjectKey={} canonicalObjectSize={} reusedOriginal={} videoRenditions={} metadata={}",
                result.jobId(),
                result.mediaId(),
                result.messageId(),
                result.status(),
                result.completedTargets(),
                result.pendingTargets(),
                result.originalObjectKey(),
                result.thumbnailObjectKey(),
                result.transcodedObjectKey(),
                result.canonicalObjectSize(),
                result.reusedOriginalObject(),
                result.videoRenditions(),
                result.videoMetadata());
    }
}
