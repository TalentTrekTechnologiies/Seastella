package com.seastella.core.internal.error;

import com.seastella.core.api.error.ApiException;
import com.seastella.core.api.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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

    /** An unmapped path is a caller mistake, not a server fault. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoRoute(NoResourceFoundException ex, HttpServletRequest request) {
        String ref = correlationId();
        log.info("no-route ref={} path={} method={}", ref, request.getRequestURI(), request.getMethod());

        ProblemDetail pd = base(HttpStatus.NOT_FOUND, "NOT_FOUND", ref, request);
        pd.setDetail("No such resource.");
        return pd;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String ref = correlationId();
        log.info("method-not-allowed ref={} path={} method={}", ref, request.getRequestURI(), request.getMethod());

        ProblemDetail pd = base(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ref, request);
        pd.setDetail("This method is not supported here.");
        return pd;
    }

    /**
     * Two people changed the same record at once and this one lost the race.
     * Expected under concurrent use, and the fix is the caller's: reload.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentUpdate(OptimisticLockingFailureException ex, HttpServletRequest request) {
        String ref = correlationId();
        log.info("concurrent-update ref={} path={} method={}", ref, request.getRequestURI(), request.getMethod());

        ProblemDetail pd = base(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", ref, request);
        pd.setDetail("Someone else changed this at the same time. Reload to see the latest, then try again.");
        return pd;
    }

    /**
     * A database constraint refused the write - normally a race the service's own
     * checks could not see (two identical saves at the same moment). The
     * constraint name and SQL stay in the log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleConstraint(DataIntegrityViolationException ex, HttpServletRequest request) {
        String ref = correlationId();
        log.warn("constraint-violation ref={} path={} method={}", ref, request.getRequestURI(), request.getMethod(), ex);

        ProblemDetail pd = base(HttpStatus.CONFLICT, "CONFLICT", ref, request);
        pd.setDetail("This change conflicts with data saved at the same time. Reload and try again.");
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
