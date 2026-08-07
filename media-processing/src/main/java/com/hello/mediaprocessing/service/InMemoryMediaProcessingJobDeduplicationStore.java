package com.hello.mediaprocessing.service;

import jakarta.inject.Singleton;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class InMemoryMediaProcessingJobDeduplicationStore implements MediaProcessingJobDeduplicationStore {

    private final Set<String> seenJobIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markIfFirstSeen(String jobId) {
        // TODO: Replace local in-memory deduplication with a distributed/durable idempotency store before multi-instance rollout.
        return seenJobIds.add(jobId);
    }
}
