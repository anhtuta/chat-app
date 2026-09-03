package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.MediaProcessingResult;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs worker results until a later phase introduces a real callback or persistence implementation.
 */
@Singleton
public class LoggingMediaProcessingResultSink implements MediaProcessingResultSink {

    private static final Logger logger = LoggerFactory.getLogger(LoggingMediaProcessingResultSink.class);

    /**
     * Logs the normalized worker result so operators can inspect Phase 4 outputs before Phase 7 integration exists.
     *
     * @param result normalized worker output for the processed job
     */
    @Override
    public void accept(MediaProcessingResult result) {
        logger.info(
                "media-processing result jobId={} mediaId={} messageId={} status={} completedTargets={} pendingTargets={} transcodedObjectKey={} reusedOriginal={} metadata={}",
                result.jobId(),
                result.mediaId(),
                result.messageId(),
                result.status(),
                result.completedTargets(),
                result.pendingTargets(),
                result.transcodedObjectKey(),
                result.reusedOriginalObject(),
                result.videoMetadata());
    }
}
