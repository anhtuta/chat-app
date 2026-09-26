package com.hello.chatapp.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.chatapp.constant.MediaProcessingJobStatus;
import com.hello.chatapp.constant.ProcessingTarget;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers callback JSON that omits empty collection fields.
 */
class MediaProcessingResultRequestTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /**
     * Verifies a successful payload without {@code pendingTargets} deserializes to an empty set.
     *
     * @throws Exception if JSON decoding fails
     */
    @Test
    void deserializesOmittedPendingTargetsAsEmptySet() throws Exception {
        String json = """
                {
                  "jobId": "media-82",
                  "messageId": 10,
                  "mediaId": 82,
                  "status": "MEDIA_READY",
                  "completedTargets": ["METADATA", "THUMBNAIL", "TRANSCODE"],
                  "originalObjectKey": "input.mov",
                  "transcodedObjectKey": "input.transcoded.mp4",
                  "canonicalObjectSize": 80,
                  "reusedOriginalObject": false,
                  "videoRenditions": []
                }
                """;

        MediaProcessingResultRequest request = OBJECT_MAPPER.readValue(json, MediaProcessingResultRequest.class);
        Set<ConstraintViolation<MediaProcessingResultRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
        assertThat(request.pendingTargets()).isEmpty();
        assertThat(request.completedTargets()).containsExactlyInAnyOrder(
                ProcessingTarget.METADATA, ProcessingTarget.THUMBNAIL, ProcessingTarget.TRANSCODE);
        assertThat(request.videoRenditions()).isEmpty();
        assertThat(request.status()).isEqualTo(MediaProcessingJobStatus.MEDIA_READY);
    }
}
