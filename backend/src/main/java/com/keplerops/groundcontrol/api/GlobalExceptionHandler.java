package com.keplerops.groundcontrol.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.keplerops.groundcontrol.domain.exception.AuthenticationException;
import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.GroundControlException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.shared.web.ErrorResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BAD_REQUEST = "bad_request";

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(DomainValidationException ex) {
        // Same envelope contract as handleConflict: only include the detail block
        // when it has content. Otherwise legacy single-arg DomainValidationException
        // throws (~30 sites across the codebase) would serialize `detail: {}`,
        // which is observable wire-format noise for every existing 422 response.
        var detail = ex.getDetail();
        var body = detail.isEmpty()
                ? ErrorResponse.of(ex.getErrorCode(), ex.getMessage())
                : ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), detail);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ErrorResponse> handleAuthorization(AuthorizationException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        // Only include the detail block when it has content; otherwise call the
        // 2-arg overload so @JsonInclude(NON_NULL) omits the field. Without this
        // guard every legacy single-arg ConflictException would start serializing
        // `detail: {}` after the cycle-2 envelope upgrade.
        var detail = ex.getDetail();
        var body = (detail == null || detail.isEmpty())
                ? ErrorResponse.of(ex.getErrorCode(), ex.getMessage())
                : ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), detail);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(ServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, Object> fieldErrors = new HashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("validation_error", "Validation failed", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        var invalidEnum = findInvalidEnumFormat(ex);
        if (invalidEnum != null) {
            return handleInvalidEnumFormat(invalidEnum);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(BAD_REQUEST, "Malformed request body"));
    }

    private InvalidFormatException findInvalidEnumFormat(Throwable ex) {
        var current = ex;
        while (current != null) {
            if (current instanceof InvalidFormatException invalidFormat
                    && invalidFormat.getTargetType() != null
                    && invalidFormat.getTargetType().isEnum()) {
                return invalidFormat;
            }
            current = current.getCause();
        }
        return null;
    }

    private ResponseEntity<ErrorResponse> handleInvalidEnumFormat(InvalidFormatException ex) {
        var field = extractFieldName(ex);
        // Use Enum.name() rather than Object.toString() so an enum that overrides
        // toString() for display purposes does not surface a non-bindable label
        // in the validValues hint the caller is told to use.
        var validValues = Arrays.stream(ex.getTargetType().getEnumConstants())
                .map(c -> ((Enum<?>) c).name())
                .toList();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", field);
        detail.put("validValues", validValues);

        var message = "Invalid value for field '" + field + "'. Valid values: " + String.join(", ", validValues);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("validation_error", message, detail));
    }

    private String extractFieldName(InvalidFormatException ex) {
        return ex.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(Objects::nonNull)
                .reduce((a, b) -> b)
                .orElse("request");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(BAD_REQUEST, message));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String message = "Request method '" + ex.getMethod() + "' is not supported";
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("method_not_allowed", message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex) {
        String message = "Missing required parameter '" + ex.getParameterName() + "'";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(BAD_REQUEST, message));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestPart(MissingServletRequestPartException ex) {
        String message = "Missing required part '" + ex.getRequestPartName() + "'";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(BAD_REQUEST, message));
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(jakarta.validation.ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("validation_error", ex.getMessage()));
    }

    /**
     * Translate database-level integrity violations into the conflict envelope.
     *
     * <p>Services across the domain layer pair preflight {@code existsBy…}
     * checks with database UNIQUE constraints (e.g. {@code (project_id, uid)}
     * on test cases, {@code (test_case_id, step_number)} on test case steps,
     * {@code (test_case_id)} on test case Gherkin). The preflight check is
     * informative for the non-concurrent case; under concurrent writes two
     * requests can both pass the {@code existsBy} check and only the database
     * UNIQUE constraint rejects the loser. Without this handler the loser
     * surfaces as a generic 500, hiding the legitimate race condition behind
     * an "internal_error" envelope.
     *
     * <p>The message body intentionally omits the constraint name and the
     * conflicting payload: constraint names are an implementation detail and
     * the original request body may contain user-controlled tokens. Clients
     * see a stable, machine-readable code (`resource_conflict`) and a generic
     * message; the per-route service-level handlers stay responsible for the
     * richer, domain-aware 409 envelopes when they win the preflight race.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation translated to 409", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        "resource_conflict",
                        "Resource conflict — the requested write violates a uniqueness invariant"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        // Spring throws this for unmapped paths in 3.2+. Without an explicit handler
        // it falls through to handleGeneric and returns 500 internal_error, which
        // muddied #821 diagnosis (a missing-controller deployment was first read as
        // a real server bug). The resource path is intentionally NOT echoed in the
        // body — keep the message stable and avoid reflecting client-controlled
        // bytes back through the error envelope.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("not_found", "Resource not found"));
    }

    @ExceptionHandler(GroundControlException.class)
    public ResponseEntity<ErrorResponse> handleGroundControl(GroundControlException ex) {
        log.error("Unhandled domain exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("internal_error", "An unexpected error occurred"));
    }
}
