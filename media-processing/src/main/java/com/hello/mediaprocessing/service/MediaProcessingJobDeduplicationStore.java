package com.hello.mediaprocessing.service;

/**
 * Tracks in-progress and completed job ids so workers can skip duplicate deliveries without
 * blocking retries for deferred or failed work.
 */
public interface MediaProcessingJobDeduplicationStore {

    /**
     * Attempts to mark a job as in-progress when processing is about to start.
     *
     * @param jobId idempotency key for a processing job
     * @return {@code true} when the job was newly claimed, otherwise {@code false} for completed or
     *         already in-progress deliveries
     */
    boolean tryBeginProcessing(String jobId);

    /**
     * Records a terminal successful completion so later duplicate deliveries can be skipped.
     *
     * @param jobId idempotency key for a processing job
     */
    void markCompleted(String jobId);

    /**
     * Releases an in-progress claim so deferred or failed jobs can be retried later.
     *
     * @param jobId idempotency key for a processing job
     */
    void releaseProcessing(String jobId);
}
