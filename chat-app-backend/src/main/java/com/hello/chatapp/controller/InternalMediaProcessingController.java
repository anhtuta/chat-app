package com.hello.chatapp.controller;

import com.hello.chatapp.config.MediaProcessingIntegrationProperties;
import com.hello.chatapp.dto.MediaProcessingResultRequest;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.service.MediaProcessingResultService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated service-to-service callback endpoint for media-processing results.
 */
@RestController
@RequestMapping("/api/internal/media-processing")
@ConditionalOnProperty(prefix = "chat.media.processing", name = "enabled", havingValue = "true")
public class InternalMediaProcessingController {

    public static final String TOKEN_HEADER = "X-Media-Processing-Token";

    private final MediaProcessingIntegrationProperties properties;
    private final MediaProcessingResultService resultService;

    public InternalMediaProcessingController(
            MediaProcessingIntegrationProperties properties,
            MediaProcessingResultService resultService) {
        this.properties = properties;
        this.resultService = resultService;
    }

    /**
     * Authenticates and applies one worker result.
     *
     * @param token shared service credential
     * @param request worker result body
     * @return empty success response after the database update commits
     */
    @PostMapping("/results")
    public ResponseEntity<Void> acceptResult(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody MediaProcessingResultRequest request) {
        requireValidToken(token);
        resultService.apply(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Compares service tokens without data-dependent early exit.
     */
    private void requireValidToken(String token) {
        byte[] expected = properties.getCallbackToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new UnauthorizedException("Invalid media-processing callback token");
        }
    }
}
