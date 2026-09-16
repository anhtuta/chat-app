package com.hello.chatapp.config;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.chatapp.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes the shared JSON error payload for HTTP failures handled outside controller advice.
 */
final class ApiErrorResponseWriter {

    private final ObjectMapper objectMapper;

    ApiErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes the common {@link ErrorResponse} body to the servlet response.
     */
    void write(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(status, message, path));
    }
}
