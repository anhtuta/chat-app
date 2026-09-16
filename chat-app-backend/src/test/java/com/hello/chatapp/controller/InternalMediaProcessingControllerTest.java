package com.hello.chatapp.controller;

import com.hello.chatapp.config.MediaProcessingIntegrationProperties;
import com.hello.chatapp.constant.MediaProcessingJobStatus;
import com.hello.chatapp.dto.MediaProcessingResultRequest;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.service.MediaProcessingResultService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers shared-token authentication for the internal media-processing callback.
 */
class InternalMediaProcessingControllerTest {

    /**
     * Verifies a matching service token delegates the callback to the result service.
     */
    @Test
    void acceptResult_validToken_appliesResult() {
        MediaProcessingResultService resultService = mock(MediaProcessingResultService.class);
        InternalMediaProcessingController controller = controller("secret-token", resultService);
        MediaProcessingResultRequest request = failedRequest();

        assertThat(controller.acceptResult("secret-token", request).getStatusCode().value()).isEqualTo(204);
        verify(resultService).apply(request);
    }

    /**
     * Verifies a missing or incorrect token is rejected before applying the callback.
     */
    @Test
    void acceptResult_invalidToken_rejected() {
        MediaProcessingResultService resultService = mock(MediaProcessingResultService.class);
        InternalMediaProcessingController controller = controller("secret-token", resultService);

        assertThatThrownBy(() -> controller.acceptResult("wrong-token", failedRequest()))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid media-processing callback token");
    }

    /**
     * Builds a controller with an explicit expected token.
     *
     * @param token expected callback credential
     * @param resultService mocked application service
     * @return controller under test
     */
    private InternalMediaProcessingController controller(
            String token, MediaProcessingResultService resultService) {
        MediaProcessingIntegrationProperties properties = new MediaProcessingIntegrationProperties();
        properties.setCallbackToken(token);
        return new InternalMediaProcessingController(properties, resultService);
    }

    /**
     * Builds a valid failed-result callback requiring no derived object.
     *
     * @return callback request
     */
    private MediaProcessingResultRequest failedRequest() {
        return new MediaProcessingResultRequest(
                "media-20",
                10L,
                20L,
                MediaProcessingJobStatus.PROCESSING_FAILED,
                null,
                Set.of(),
                Set.of(),
                "input.mov",
                null,
                null,
                null,
                false,
                java.util.List.of());
    }
}
