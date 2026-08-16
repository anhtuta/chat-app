package com.hello.mediaprocessing.service;

import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a single-node in-memory idempotency check for duplicate job deliveries.
 */
@Singleton
public class InMemoryMediaProcessingJobDeduplicationStore implements MediaProcessingJobDeduplicationStore {

    private final Set<String> completedJobIds = ConcurrentHashMap.newKeySet();
    private final Set<String> inProgressJobIds = ConcurrentHashMap.newKeySet();

    /**
     * Claims a job for processing unless it is already completed or currently in progress.
     *
     * @param jobId idempotency key for a processing job
     * @return {@code true} when the job id is newly marked in-progress
     */
    @Override
    public boolean tryBeginProcessing(String jobId) {
        // TODO: Replace local in-memory deduplication with a distributed/durable idempotency store before multi-instance rollout.
        if (completedJobIds.contains(jobId)) {
            return false;
        }
        return inProgressJobIds.add(jobId);
    }

    /**
     * Moves a job from in-progress to completed so duplicate deliveries are skipped afterward.
     *
     * @param jobId idempotency key for a processing job
     */
    @Override
    public void markCompleted(String jobId) {
        completedJobIds.add(jobId);
        inProgressJobIds.remove(jobId);
    }

    /**
     * Clears an in-progress claim so the job can be retried after a deferral or failure.
     *
     * @param jobId idempotency key for a processing job
     */
    @Override
    public void releaseProcessing(String jobId) {
        inProgressJobIds.remove(jobId);
    }
}
