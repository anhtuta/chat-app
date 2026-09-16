package com.hello.chatapp.exception;

import com.hello.chatapp.dto.ErrorResponse;
import com.hello.chatapp.entity.Message;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for HTTP mapping of unexpected vs client errors in {@link GlobalExceptionHandler}.
 */
@SuppressWarnings("null")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Failed login maps to 401 JSON with the domain message, not a plain string.
     */
    @Test
    void handleUnauthorizedException_returnsErrorResponse() {
        MockHttpServletRequest request = request("/api/auth/login");

        ResponseEntity<ErrorResponse> response = handler.handleUnauthorizedException(
                new UnauthorizedException("Invalid username or password"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getError()).isEqualTo("Unauthorized");
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid username or password");
        assertThat(response.getBody().getPath()).isEqualTo("/api/auth/login");
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }

    /**
     * Concurrent message edit/delete ({@code @Version}) must be 409 with a stable client message.
     */
    @Test
    void handleOptimisticLockingFailure_returnsConflictWithoutHibernateDetails() {
        OptimisticLockingFailureException ex =
                new ObjectOptimisticLockingFailureException(Message.class, 10L);
        MockHttpServletRequest request = request("/api/messages/10");

        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLockingFailure(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(409);
        assertThat(response.getBody().getMessage()).isEqualTo(
                "This message was updated by someone else. Refresh and try again.");
        assertThat(response.getBody().getMessage()).doesNotContain("ObjectOptimisticLockingFailureException");
        assertThat(response.getBody().getPath()).isEqualTo("/api/messages/10");
    }

    /**
     * Chrome DevTools (and similar) missing-static probes are 404, not a 500 with a stack trace.
     */
    @Test
    @SuppressWarnings("null")
    void handleNoResourceFound_returnsNotFound() {
        NoResourceFoundException ex = new NoResourceFoundException(
                HttpMethod.GET, "/.well-known/appspecific/com.chrome.devtools.json");
        MockHttpServletRequest request = request("/.well-known/appspecific/com.chrome.devtools.json");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Not found");
    }

    /**
     * Unexpected failures use the generic 500 message and do not leak the exception text.
     */
    @Test
    void handleAllExceptions_returnsGenericMessage() {
        MockHttpServletRequest request = request("/api/groups");

        ResponseEntity<ErrorResponse> response = handler.handleAllExceptions(
                new RuntimeException("secret jdbc detail"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo(
                "An unexpected error occurred. Please contact support.");
        assertThat(response.getBody().getMessage()).doesNotContain("jdbc");
        assertThat(response.getBody().getPath()).isEqualTo("/api/groups");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
