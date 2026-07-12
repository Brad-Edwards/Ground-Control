package com.keplerops.groundcontrol.domain.exception;

/**
 * A required backend subsystem is not available to serve the request — for example the Temporal
 * workflow control surface when {@code groundcontrol.temporal.control.enabled} is off. Maps to HTTP
 * 503 via {@code GlobalExceptionHandler} so callers get the standard {@code ErrorResponse} envelope
 * rather than a leaked wiring error.
 */
public class ServiceUnavailableException extends GroundControlException {

    public ServiceUnavailableException(String message) {
        super(message, "service_unavailable");
    }
}
