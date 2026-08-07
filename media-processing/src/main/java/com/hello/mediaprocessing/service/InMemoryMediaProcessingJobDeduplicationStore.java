package com.hello.mediaprocessing.service;

import jakarta.inject.Singleton;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a single-node in-memory idempotency check for duplicate job deliveries.
 */
@Singleton
public class InMemoryMediaProcessingJobDeduplicationStore implements MediaProcessingJobDeduplicationStore {

    private final Set<String> seenJobIds = ConcurrentHashMap.newKeySet();

    /**
     * Records a job id in the local in-memory set and reports whether it was first seen.
     *
     * @param jobId idempotency key for a processing job
     * @return {@code true} when the job id is new to this process
     */
    @Override
    public boolean markIfFirstSeen(String jobId) {
        // TODO: Replace local in-memory deduplication with a distributed/durable idempotency store before multi-instance rollout.
        return seenJobIds.add(jobId);
    }
}
