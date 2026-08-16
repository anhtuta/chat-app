package com.hello.chatapp.exception;

import com.hello.chatapp.entity.Message;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for HTTP mapping of unexpected vs client errors in {@link GlobalExceptionHandler}.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Concurrent message edit/delete ({@code @Version}) must be 409 with a stable client message.
     */
    @Test
    void handleOptimisticLockingFailure_returnsConflictWithoutHibernateDetails() {
        OptimisticLockingFailureException ex =
                new ObjectOptimisticLockingFailureException(Message.class, 10L);

        ResponseEntity<String> response = handler.handleOptimisticLockingFailure(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(
                "This message was updated by someone else. Refresh and try again.");
        assertThat(response.getBody()).doesNotContain("ObjectOptimisticLockingFailureException");
    }

    /**
     * Chrome DevTools (and similar) missing-static probes are 404, not a 500 with a stack trace.
     */
    @Test
    @SuppressWarnings("null")
    void handleNoResourceFound_returnsNotFound() {
        NoResourceFoundException ex = new NoResourceFoundException(
                HttpMethod.GET, "/.well-known/appspecific/com.chrome.devtools.json");

        ResponseEntity<String> response = handler.handleNoResourceFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo("Not found");
    }
}
