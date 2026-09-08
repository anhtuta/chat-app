package com.hello.mediaprocessing.service;

import com.hello.mediaprocessing.config.MediaProcessingCallbackProperties;
import com.hello.mediaprocessing.messaging.ChatBackendMediaProcessingClient;
import com.hello.mediaprocessing.model.MediaProcessingResult;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Reports worker results to chat-app-backend when Phase 7 callback integration is enabled.
 */
@Singleton
@Requires(property = "media-processing.callback.enabled", value = "true")
public class ChatBackendMediaProcessingResultSink implements MediaProcessingResultSink {

    private final ChatBackendMediaProcessingClient client;
    private final MediaProcessingCallbackProperties properties;

    public ChatBackendMediaProcessingResultSink(
            ChatBackendMediaProcessingClient client,
            MediaProcessingCallbackProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Calls the backend synchronously so a failed callback prevents the RabbitMQ message from being acknowledged.
     *
     * @param result normalized worker output
     */
    @Override
    public void accept(MediaProcessingResult result) {
        client.report(properties.getToken(), result);
    }
}
