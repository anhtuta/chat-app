package com.hello.mediaprocessing.service;

import jakarta.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides a single-node in-memory idempotency check for duplicate job deliveries.
 */
@Singleton
public class InMemoryMediaProcessingJobDeduplicationStore implements MediaProcessingJobDeduplicationStore {

    private final ConcurrentHashMap<String, JobState> jobStates = new ConcurrentHashMap<>();

    /**
     * Claims a job for processing unless it is already completed or currently in progress.
     *
     * @param jobId idempotency key for a processing job
     * @return {@code true} when the job id is newly marked in-progress
     */
    @Override
    public boolean tryBeginProcessing(String jobId) {
        // TODO: Replace local in-memory deduplication with a distributed/durable idempotency store before multi-instance rollout.
        AtomicBoolean claimed = new AtomicBoolean(false);
        jobStates.compute(jobId, (id, current) -> {
            if (current == JobState.COMPLETED || current == JobState.IN_PROGRESS) {
                return current;
            }
            claimed.set(true);
            return JobState.IN_PROGRESS;
        });
        return claimed.get();
    }

    /**
     * Moves a job from in-progress to completed so duplicate deliveries are skipped afterward.
     *
     * @param jobId idempotency key for a processing job
     */
    @Override
    public void markCompleted(String jobId) {
        jobStates.compute(jobId, (id, current) -> JobState.COMPLETED);
    }

    /**
     * Clears an in-progress claim so the job can be retried after a deferral or failure.
     *
     * @param jobId idempotency key for a processing job
     */
    @Override
    public void releaseProcessing(String jobId) {
        jobStates.computeIfPresent(jobId, (id, current) -> current == JobState.IN_PROGRESS ? null : current);
    }

    /**
     * Lifecycle states tracked for a single processing job id.
     */
    private enum JobState {
        IN_PROGRESS,
        COMPLETED
    }
}
