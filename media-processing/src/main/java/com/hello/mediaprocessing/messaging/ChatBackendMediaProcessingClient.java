package com.hello.mediaprocessing.messaging;

import com.hello.mediaprocessing.model.MediaProcessingResult;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.annotation.Client;

/**
 * Declarative client for the chat backend's internal media-processing callback.
 */
@Client("${media-processing.callback.base-url}")
public interface ChatBackendMediaProcessingClient {

    /**
     * Reports one processing result to chat-app-backend.
     *
     * @param token shared callback credential
     * @param result normalized worker result
     * @return backend acknowledgment
     */
    @Post("/api/internal/media-processing/results")
    HttpResponse<Void> report(
            @Header("X-Media-Processing-Token") String token,
            @Body MediaProcessingResult result);
}
