package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers job-id matching for the local trigger result holder.
 */
class LastMediaProcessingResultHolderTest {

    /**
     * Verifies that a stored result is returned only when the job id matches.
     */
    @Test
    void getForJob_returnsResultForMatchingJobId() {
        LastMediaProcessingResultHolder holder = new LastMediaProcessingResultHolder();
        holder.set(result("job-1"));

        assertThat(holder.getForJob("job-1")).isNotNull();
        assertThat(holder.getForJob("job-2")).isNull();
    }

    /**
     * Builds a minimal result for holder tests.
     *
     * @param jobId job identifier to embed
     * @return result payload
     */
    private MediaProcessingResult result(String jobId) {
        return new MediaProcessingResult(
                jobId,
                0L,
                0L,
                MediaProcessingJobStatus.MEDIA_READY,
                null,
                Set.of(),
                Set.of(),
                "source.mp4",
                null,
                null,
                false);
    }
}
