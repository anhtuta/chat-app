package com.hello.mediaprocessing.model;

import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.constant.ProcessingTarget;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers callback JSON so empty target sets are not omitted by Micronaut Serde.
 */
@MicronautTest(startApplication = false)
class MediaProcessingResultSerdeTest {

    @Inject
    JsonMapper jsonMapper;

    /**
     * Verifies a completed job still serializes {@code pendingTargets} as an empty array.
     *
     * @throws Exception if JSON encoding fails
     */
    @Test
    void serializesEmptyPendingTargetsAsEmptyArray() throws Exception {
        MediaProcessingResult result = new MediaProcessingResult(
                "media-82",
                10L,
                82L,
                MediaProcessingJobStatus.MEDIA_READY,
                null,
                Set.of(ProcessingTarget.METADATA, ProcessingTarget.THUMBNAIL, ProcessingTarget.TRANSCODE),
                Set.of(),
                "input.mov",
                "input.thumbnail.jpg",
                "input.transcoded.mp4",
                80L,
                false,
                List.of());

        String json = new String(jsonMapper.writeValueAsBytes(result), StandardCharsets.UTF_8);

        assertThat(json).contains("\"pendingTargets\":[]");
        assertThat(json).contains("\"completedTargets\":");
        assertThat(json).contains("\"videoRenditions\":[]");
    }
}
