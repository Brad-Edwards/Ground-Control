package com.keplerops.groundcontrol.domain.exception;

/**
 * A required backend subsystem is not available to serve the request. Maps to HTTP 503 via {@code
 * GlobalExceptionHandler} so callers get the standard {@code ErrorResponse} envelope rather than a
 * leaked wiring error.
 */
public class ServiceUnavailableException extends GroundControlException {

    public ServiceUnavailableException(String message) {
        super(message, "service_unavailable");
    }
}
