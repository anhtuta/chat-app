package com.hello.mediaprocessing.service;

/**
 * Tracks job ids that have already been observed so workers can skip duplicate deliveries.
 */
public interface MediaProcessingJobDeduplicationStore {

    /**
     * Marks a job id as seen if this is the first delivery attempt handled by the current store.
     *
     * @param jobId idempotency key for a processing job
     * @return {@code true} when the job id was new, otherwise {@code false}
     */
    boolean markIfFirstSeen(String jobId);
}
