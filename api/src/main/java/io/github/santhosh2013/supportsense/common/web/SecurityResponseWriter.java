package io.github.santhosh2013.supportsense.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.santhosh2013.supportsense.common.domain.TimeSource;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Writes the exact same RFC-7807 ProblemDetail shape as {@link GlobalExceptionHandler}, for
 * the two failure modes Spring Security rejects before a request ever reaches the
 * dispatcher: no/invalid credentials (401) and authenticated-but-forbidden (403). Kept
 * separate from GlobalExceptionHandler because @RestControllerAdvice only applies to
 * exceptions thrown inside the dispatcher — these are rejected earlier, in the filter chain.
 */
public final class SecurityResponseWriter {

    private static final String PROBLEM_BASE = "https://supportsense.dev/problems/";

    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;

    public SecurityResponseWriter(ObjectMapper objectMapper, TimeSource timeSource) {
        this.objectMapper = objectMapper;
        this.timeSource = timeSource;
    }

    public void write(
            HttpServletResponse response, HttpStatus status, String detail, String problemType)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + problemType));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("timestamp", timeSource.now());

        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
