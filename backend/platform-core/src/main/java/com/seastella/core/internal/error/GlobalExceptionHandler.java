package com.seastella.core.internal.error;

import com.seastella.core.api.error.ApiException;
import com.seastella.core.api.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Single exit point for every error response (SEC-21).
 *
 * <p>Two rules hold throughout:
 *
 * <ol>
 *   <li><b>Nothing internal escapes.</b> Stack traces, SQL, driver messages,
 *       class names and internal ids stay in the log. The caller receives a
 *       stable code, a safe title, and a correlation id they can quote to
 *       support.</li>
 *   <li><b>Unexpected means 500 and a generic body.</b> An exception this class
 *       does not recognise is a bug, not a message to forward - forwarding
 *       {@code getMessage()} from an arbitrary throwable is how connection
 *       strings end up in HTTP responses.</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI DEFAULT_TYPE = URI.create("about:blank");

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        String ref = correlationId();
        // 4xx below 500 are expected control flow; log at INFO without a trace.
        log.info("api-error ref={} code={} status={} path={} msg={}",
                ref, ex.getCode(), ex.getStatus().value(), request.getRequestURI(), ex.getMessage());

        ProblemDetail pd = base(ex.getStatus(), ex.getCode(), ref, request);
        pd.setDetail(ex.isMessageSafeToExpose() ? ex.getMessage() : safeTitleFor(ex.getStatus()));

        if (ex instanceof ValidationException ve && !ve.getFieldErrors().isEmpty()) {
            pd.setProperty("fieldErrors", ve.getFieldErrors());
        }
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex,
                                              HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fields.putIfAbsent(fe.getField(),
                        fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()));

        String ref = correlationId();
        log.info("validation-error ref={} path={} fields={}", ref, request.getRequestURI(), fields.keySet());

        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ref, request);
        pd.setDetail("One or more fields are invalid.");
        pd.setProperty("fieldErrors", fields);
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String ref = correlationId();
        // Denials are security-relevant: they are logged as a distinct event (SEC-24).
        log.warn("access-denied ref={} path={} method={}", ref, request.getRequestURI(), request.getMethod());

        ProblemDetail pd = base(HttpStatus.FORBIDDEN, "FORBIDDEN", ref, request);
        pd.setDetail("You do not have permission to perform this action.");
        return pd;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        String ref = correlationId();
        log.warn("authentication-failed ref={} path={}", ref, request.getRequestURI());

        ProblemDetail pd = base(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ref, request);
        pd.setDetail("Authentication is required.");
        return pd;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                              HttpServletRequest request) {
        String ref = correlationId();
        log.info("upload-too-large ref={} path={}", ref, request.getRequestURI());

        ProblemDetail pd = base(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", ref, request);
        pd.setDetail("The uploaded file exceeds the maximum permitted size.");
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex,
                                          HttpServletRequest request) {
        String ref = correlationId();
        // Deliberately does not echo the parse error: it quotes the raw payload.
        log.info("unreadable-body ref={} path={}", ref, request.getRequestURI());

        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", ref, request);
        pd.setDetail("The request body could not be read.");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        String ref = correlationId();
        log.error("unexpected-error ref={} path={} method={}",
                ref, request.getRequestURI(), request.getMethod(), ex);

        ProblemDetail pd = base(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ref, request);
        pd.setDetail("An unexpected error occurred. Quote the reference when contacting support.");
        return pd;
    }

    private ProblemDetail base(HttpStatus status, String code, String ref, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(DEFAULT_TYPE);
        pd.setTitle(safeTitleFor(status));
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("code", code);
        pd.setProperty("reference", ref);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private static String safeTitleFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Not found";
            case FORBIDDEN -> "Forbidden";
            case UNAUTHORIZED -> "Unauthorized";
            case CONFLICT -> "Conflict";
            case BAD_REQUEST -> "Bad request";
            case PAYLOAD_TOO_LARGE -> "Payload too large";
            default -> "Request failed";
        };
    }

    private static String correlationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
