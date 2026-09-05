package io.github.santhosh2013.supportsense.common.web;

import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single source of error responses. Every error is an RFC-7807 ProblemDetail —
 * there is no bespoke error class anywhere in the codebase.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://supportsense.dev/problems/";

    private final TimeSource timeSource;

    public GlobalExceptionHandler(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = create(HttpStatus.BAD_REQUEST, "Validation failed", "validation-failed");

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException exception) {
        log.debug("Malformed request body", exception);
        return create(HttpStatus.BAD_REQUEST, "Malformed request body", "malformed-request");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException exception) {
        log.debug("Authentication failed", exception);
        // Deliberately generic — never reveal whether the account exists.
        return create(HttpStatus.UNAUTHORIZED, "Invalid credentials", "authentication-failed");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        log.debug("Access denied", exception);
        return create(HttpStatus.FORBIDDEN, "Access denied", "access-denied");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return create(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "internal-error");
    }

    private ProblemDetail create(HttpStatus status, String detail, String problemType) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + problemType));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("timestamp", timeSource.now());
        return problem;
    }
}
