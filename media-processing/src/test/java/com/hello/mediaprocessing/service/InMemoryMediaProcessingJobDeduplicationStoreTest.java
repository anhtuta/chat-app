package com.hello.mediaprocessing.service;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers atomic claim, completion, and release behavior for in-memory job deduplication.
 */
class InMemoryMediaProcessingJobDeduplicationStoreTest {

    /**
     * Verifies that a completed job cannot be claimed again.
     */
    @Test
    void tryBeginProcessing_afterCompletion_rejectsClaim() {
        InMemoryMediaProcessingJobDeduplicationStore store = new InMemoryMediaProcessingJobDeduplicationStore();

        assertThat(store.tryBeginProcessing("job-1")).isTrue();
        store.markCompleted("job-1");

        assertThat(store.tryBeginProcessing("job-1")).isFalse();
    }

    /**
     * Verifies that releasing an in-progress claim allows a later retry.
     */
    @Test
    void releaseProcessing_allowsRetryAfterFailure() {
        InMemoryMediaProcessingJobDeduplicationStore store = new InMemoryMediaProcessingJobDeduplicationStore();

        assertThat(store.tryBeginProcessing("job-retry")).isTrue();
        store.releaseProcessing("job-retry");

        assertThat(store.tryBeginProcessing("job-retry")).isTrue();
    }

    /**
     * Verifies that concurrent completion and claim attempts cannot observe a post-completion claim.
     */
    @Test
    void concurrentCompletionAndClaims_preventsLateClaims() throws Exception {
        InMemoryMediaProcessingJobDeduplicationStore store = new InMemoryMediaProcessingJobDeduplicationStore();
        String jobId = "job-race";
        assertThat(store.tryBeginProcessing(jobId)).isTrue();

        int challengerCount = 64;
        CyclicBarrier startBarrier = new CyclicBarrier(challengerCount + 1);
        AtomicInteger successfulClaims = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(challengerCount + 1);

        try {
            executor.submit(() -> {
                try {
                    interleavedCompletion(store, jobId, startBarrier);
                } catch (Exception e) {
                    throw new IllegalStateException("Concurrent completion failed", e);
                }
            });
            for (int i = 0; i < challengerCount; i++) {
                executor.submit(() -> {
                    try {
                        interleavedClaim(store, jobId, startBarrier, successfulClaims);
                    } catch (Exception e) {
                        throw new IllegalStateException("Concurrent claim failed", e);
                    }
                });
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successfulClaims).hasValue(0);
        assertThat(store.tryBeginProcessing(jobId)).isFalse();
    }

    /**
     * Marks a job complete at the same time competing claim attempts are released.
     *
     * @param store deduplication store under test
     * @param jobId job id being completed
     * @param startBarrier barrier used to align concurrent threads
     */
    private void interleavedCompletion(
            InMemoryMediaProcessingJobDeduplicationStore store,
            String jobId,
            CyclicBarrier startBarrier) throws Exception {
        startBarrier.await();
        store.markCompleted(jobId);
    }

    /**
     * Attempts to claim a job at the same time completion is being recorded.
     *
     * @param store deduplication store under test
     * @param jobId job id being claimed
     * @param startBarrier barrier used to align concurrent threads
     * @param successfulClaims counter for claims that incorrectly succeed
     */
    private void interleavedClaim(
            InMemoryMediaProcessingJobDeduplicationStore store,
            String jobId,
            CyclicBarrier startBarrier,
            AtomicInteger successfulClaims) throws Exception {
        startBarrier.await();
        if (store.tryBeginProcessing(jobId)) {
            successfulClaims.incrementAndGet();
        }
    }
}
