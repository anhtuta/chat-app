package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingLocalTriggerProperties;
import com.hello.mediaprocessing.config.MediaProcessingStorageProperties;
import com.hello.mediaprocessing.constant.MediaProcessingMessageType;
import com.hello.mediaprocessing.constant.ObjectStorageProviderType;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import com.hello.mediaprocessing.dto.LocalMediaProcessingTriggerRequest;
import com.hello.mediaprocessing.model.MediaProcessingJobMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers mapping from the local HTTP trigger body to a worker job payload.
 */
class LocalMediaProcessingJobFactoryTest {

    /**
     * Verifies defaults for bucket, MIME type, targets, and generated job identity.
     */
    @Test
    void toJob_fillsDefaults() {
        MediaProcessingJobMessage job = newFactory().toJob(new LocalMediaProcessingTriggerRequest(
                "media/7/video/demo.mov", null, null, null, null, null, null));

        assertThat(job.objectKey()).isEqualTo("media/7/video/demo.mov");
        assertThat(job.bucket()).isEqualTo("chat-media");
        assertThat(job.requestedMimeType()).isEqualTo("video/mp4");
        assertThat(job.messageType()).isEqualTo(MediaProcessingMessageType.VIDEO);
        assertThat(job.storageProvider()).isEqualTo(ObjectStorageProviderType.MINIO);
        assertThat(job.processingTargets()).containsExactly(ProcessingTarget.METADATA, ProcessingTarget.TRANSCODE);
        assertThat(job.jobId()).isNotBlank();
        assertThat(job.messageId()).isZero();
        assertThat(job.mediaId()).isZero();
    }

    /**
     * Verifies that explicit request fields are preserved on the job payload.
     */
    @Test
    void toJob_keepsProvidedFields() {
        MediaProcessingJobMessage job = newFactory().toJob(new LocalMediaProcessingTriggerRequest(
                "clips/a.webm",
                "other-bucket",
                "video/webm",
                List.of(ProcessingTarget.TRANSCODE),
                "job-9",
                11L,
                22L));

        assertThat(job.bucket()).isEqualTo("other-bucket");
        assertThat(job.requestedMimeType()).isEqualTo("video/webm");
        assertThat(job.processingTargets()).containsExactly(ProcessingTarget.TRANSCODE);
        assertThat(job.jobId()).isEqualTo("job-9");
        assertThat(job.messageId()).isEqualTo(11L);
        assertThat(job.mediaId()).isEqualTo(22L);
    }

    /**
     * Creates a factory with the default MinIO and local-trigger settings.
     *
     * @return factory under test
     */
    private LocalMediaProcessingJobFactory newFactory() {
        return new LocalMediaProcessingJobFactory(
                new MediaProcessingLocalTriggerProperties(), new MediaProcessingStorageProperties());
    }
}
