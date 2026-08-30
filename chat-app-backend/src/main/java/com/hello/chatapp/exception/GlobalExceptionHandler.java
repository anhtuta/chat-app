package com.hello.chatapp.exception;

import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.hello.chatapp.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Maps domain and framework exceptions to HTTP / STOMP responses.
 * HTTP failures use {@link ErrorResponse} JSON so clients can read {@code message} consistently.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps failed login (and similar) to 401 with a stable {@link ErrorResponse}.
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(
            UnauthorizedException e, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage(), request);
    }

    /**
     * Maps missing resources to 404 with {@link ErrorResponse}.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(
            NotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    /**
     * Missing static resource (often Chrome DevTools probing
     * {@code /.well-known/appspecific/com.chrome.devtools.json}). Not an application failure:
     * log without a stack trace and return 404 instead of the 500 catch-all.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException e, HttpServletRequest request) {
        logger.warn("No static resource: {}", e.getResourcePath());
        return error(HttpStatus.NOT_FOUND, "Not found", request);
    }

    /**
     * Maps client input errors to 400 with {@link ErrorResponse}.
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(
            BadRequestException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    /**
     * Bean Validation failures on {@code @RequestBody} / {@code @Valid} — client error, not 500.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Validation failed";
        }
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Malformed JSON / unreadable body — client error, not 500.
     * Avoid returning parser internals; keep a stable message for clients.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpServletRequest request) {
        logger.warn("Malformed request body: {}", e.getMostSpecificCause().getMessage());
        return error(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    /**
     * Maps authorization failures to 403 with {@link ErrorResponse}.
     */
    @ExceptionHandler({ForbiddenException.class, SecurityException.class})
    public ResponseEntity<ErrorResponse> handleForbiddenException(
            RuntimeException e, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, e.getMessage(), request);
    }

    /**
     * Concurrent {@code Message} edit/delete (JPA {@code @Version}) — loser gets 409, not 500.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
            OptimisticLockingFailureException e, HttpServletRequest request) {
        logger.warn("Optimistic lock conflict: {}", e.getMessage());
        return error(
                HttpStatus.CONFLICT,
                "This message was updated by someone else. Refresh and try again.",
                request);
    }

    @MessageExceptionHandler(ForbiddenException.class)
    // @SendToUser("/queue/errors")
    public void handleForbiddenMessageException(ForbiddenException e) {
        // TODO send error message to user via WebSocket
        logger.error("ForbiddenException in WebSocket: {}", e.getMessage());
        // Handle the ForbiddenException for WebSocket messages
        return;
    }

    /**
     * Catch-all for unexpected errors. Do not return {@code ex.getMessage()} — JDBC/Hibernate
     * RuntimeExceptions include SQL and schema details that must stay in server logs only.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex, HttpServletRequest request) {
        logger.error("Internal Server Error occurred: ", ex);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please contact support.",
                request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message, HttpServletRequest request) {
        String resolvedMessage = (message == null || message.isBlank()) ? status.getReasonPhrase() : message;
        return ResponseEntity.status(Objects.requireNonNull(status))
                .body(ErrorResponse.of(status, resolvedMessage, request));
    }
}
