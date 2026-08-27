package com.hello.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.chatapp.dto.ErrorResponse;

/**
 * Unit tests for writing the shared JSON error payload outside controller advice.
 */
class ApiErrorResponseWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ApiErrorResponseWriter writer = new ApiErrorResponseWriter(objectMapper);

    /**
     * Security-level auth failures should serialize the same error schema as controller failures.
     */
    @Test
    void write_returnsSharedErrorResponseJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, HttpStatus.UNAUTHORIZED, "User is not authenticated", "/api/test/protected");

        ErrorResponse body = objectMapper.readValue(response.getContentAsByteArray(), ErrorResponse.class);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(body.getStatus()).isEqualTo(401);
        assertThat(body.getError()).isEqualTo("Unauthorized");
        assertThat(body.getMessage()).isEqualTo("User is not authenticated");
        assertThat(body.getPath()).isEqualTo("/api/test/protected");
        assertThat(body.getTimestamp()).isNotNull();
    }
}
