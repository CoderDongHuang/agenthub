package com.agenthub.common.exception;

import com.agenthub.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        String message = exception.getReason();
        if (message == null || message.isBlank()) message = "Request failed";
        if (message.length() > 240) message = message.substring(0, 240);
        return ResponseEntity.status(exception.getStatusCode()).body(ApiResponse.error(status, message));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("Unhandled exception", exception);
        return ApiResponse.error(500, "Internal server error");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException exception) {
        log.debug("Invalid request", exception);
        return ApiResponse.error(400, safeMessage(exception, "Invalid request"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NoSuchElementException exception) {
        return ApiResponse.error(404, safeMessage(exception, "Resource not found"));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConflict(IllegalStateException exception) {
        log.debug("Request conflicts with current state", exception);
        return ApiResponse.error(409, safeMessage(exception, "Operation conflicts with current state"));
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleSecurity(SecurityException exception) {
        return ApiResponse.error(403, "Request verification failed");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return ApiResponse.error(413, "File exceeds the 25 MB limit");
    }

    private String safeMessage(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return fallback;
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
