package com.hello.chatapp.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
import jakarta.servlet.http.HttpServletRequest;

/**
 * Maps domain and framework exceptions to HTTP / STOMP responses.
 * 4xx bodies are plain strings so the frontend can use {@code response.text()}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<String> handleUnauthorizedException(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> handleNotFoundException(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(e.getMessage());
    }

    /**
     * Missing static resource (often Chrome DevTools probing
     * {@code /.well-known/appspecific/com.chrome.devtools.json}). Not an application failure:
     * log without a stack trace and return 404 instead of the 500 catch-all.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleNoResourceFound(NoResourceFoundException e) {
        logger.warn("No static resource: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Not found");
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<String> handleBadRequestException(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(e.getMessage());
    }

    /**
     * Bean Validation failures on {@code @RequestBody} / {@code @Valid} — client error, not 500.
     * Body stays a plain string like other 4xx handlers so FE {@code response.text()} keeps working.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Validation failed";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
    }

    /**
     * Malformed JSON / unreadable body — client error, not 500.
     * Avoid returning parser internals; keep a stable message for clients.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        logger.warn("Malformed request body: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Malformed request body");
    }

    @ExceptionHandler({ForbiddenException.class, SecurityException.class})
    public ResponseEntity<String> handleForbiddenException(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(e.getMessage());
    }

    /**
     * Concurrent {@code Message} edit/delete (JPA {@code @Version}) — loser gets 409, not 500.
     * Body is a stable string so FE {@code response.text()} can show it after refresh.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<String> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        logger.warn("Optimistic lock conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body("This message was updated by someone else. Refresh and try again.");
    }

    @MessageExceptionHandler(ForbiddenException.class)
    // @SendToUser("/queue/errors")
    public void handleForbiddenMessageException(ForbiddenException e) {
        // TODO send error message to user via WebSocket
        logger.error("ForbiddenException in WebSocket: {}", e.getMessage());
        // Handle the ForbiddenException for WebSocket messages
        return;
    }

    // Catch-all for unexpected errors. Do not return ex.getMessage() — JDBC/Hibernate
    // RuntimeExceptions include SQL and schema details that must stay in server logs only.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllExceptions(Exception ex, HttpServletRequest request) {
        logger.error("Internal Server Error occurred: ", ex);

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Internal Server Error");
        body.put("message", "An unexpected error occurred. Please contact support.");
        body.put("path", request.getRequestURI());

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
