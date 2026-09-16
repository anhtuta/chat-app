package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.model.MediaProcessingResult;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the most recent worker result so the local HTTP trigger can return it to the caller.
 * LastMediaProcessingResultHolder is there because handle() only returns a status. The useful payload (transcodedObjectKey,
 * metadata, completed targets) is sent to MediaProcessingResultSink, not back to the caller.
 * The local controller needs that payload in the HTTP response. Changing MediaProcessingJobHandler to return
 * MediaProcessingResult would have been the clean fix.
 * This holder is a local-debug workaround, not a concurrency primitive.
 */
@Singleton
@Requires(property = "media-processing.local-trigger.enabled", value = "true")
public class LastMediaProcessingResultHolder {

    private final AtomicReference<MediaProcessingResult> lastResult = new AtomicReference<>();

    /**
     * Stores the latest worker result, replacing any previous value.
     *
     * @param result normalized worker output
     */
    public void set(MediaProcessingResult result) {
        lastResult.set(result);
    }

    /**
     * Returns the stored result when it belongs to {@code jobId}, otherwise {@code null}.
     *
     * @param jobId job that was just handled
     * @return matching result, or {@code null} when the handler never emitted one for this job
     */
    public MediaProcessingResult getForJob(String jobId) {
        MediaProcessingResult result = lastResult.get();
        if (result == null || jobId == null || !jobId.equals(result.jobId())) {
            return null;
        }
        return result;
    }
}
