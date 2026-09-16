package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingCallbackProperties;
import com.hello.mediaprocessing.constant.MediaProcessingJobStatus;
import com.hello.mediaprocessing.messaging.ChatBackendMediaProcessingClient;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import io.micronaut.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers forwarding worker results and callback credentials to chat-app-backend.
 */
class ChatBackendMediaProcessingResultSinkTest {

    /**
     * Verifies the sink sends the configured token and unchanged result payload.
     */
    @Test
    void accept_reportsResultWithConfiguredToken() {
        RecordingClient client = new RecordingClient();
        MediaProcessingCallbackProperties properties = new MediaProcessingCallbackProperties();
        properties.setToken("secret-token");
        ChatBackendMediaProcessingResultSink sink =
                new ChatBackendMediaProcessingResultSink(client, properties);
        MediaProcessingResult result = result();

        sink.accept(result);

        assertThat(client.token).isEqualTo("secret-token");
        assertThat(client.result).isSameAs(result);
    }

    /**
     * Builds a minimal successful result.
     *
     * @return worker result for the test
     */
    private MediaProcessingResult result() {
        return new MediaProcessingResult(
                "media-20",
                10L,
                20L,
                MediaProcessingJobStatus.MEDIA_READY,
                null,
                Set.of(),
                Set.of(),
                "input.mov",
                "input.thumbnail.jpg",
                "input.transcoded.mp4",
                80L,
                false,
                java.util.List.of());
    }

    /**
     * Records the callback invocation without making an HTTP request.
     */
    private static final class RecordingClient implements ChatBackendMediaProcessingClient {

        private String token;
        private MediaProcessingResult result;

        /**
         * Records callback arguments.
         *
         * @param token shared callback credential
         * @param result normalized worker result
         * @return no-content acknowledgment
         */
        @Override
        public HttpResponse<Void> report(String token, MediaProcessingResult result) {
            this.token = token;
            this.result = result;
            return HttpResponse.noContent();
        }
    }
}
