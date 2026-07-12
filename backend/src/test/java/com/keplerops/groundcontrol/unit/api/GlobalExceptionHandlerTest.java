package com.keplerops.groundcontrol.unit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.keplerops.groundcontrol.api.GlobalExceptionHandler;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.shared.web.ErrorResponse;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleHttpMessageNotReadable_returnsBadRequest() {
        var ex = new HttpMessageNotReadableException("bad json");

        var response = handler.handleHttpMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("bad_request");
        assertThat(response.getBody().error().message()).isEqualTo("Malformed request body");
    }

    @Test
    void handleHttpMessageNotReadable_returnsValidationErrorForInvalidEnumValue() {
        var mapper = new ObjectMapper();
        var cause = assertThrows(
                InvalidFormatException.class,
                () -> mapper.readValue("{\"status\":\"PROPOSED\"}", ControlTestConclusionRequest.class));
        var ex = new HttpMessageNotReadableException("bad enum", cause);

        var response = handler.handleHttpMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("validation_error");
        assertThat(response.getBody().error().message()).contains("Invalid value for field 'status'");
        assertThat(response.getBody().error().detail()).containsEntry("field", "status");
        assertThat(response.getBody().error().detail().get("validValues"))
                .asList()
                .contains("EFFECTIVE", "INEFFECTIVE", "NOT_TESTED");
    }

    @Test
    void handleHttpMessageNotReadable_returnsLeafFieldNameForNestedInvalidEnumValue() {
        // The Jackson path on a nested enum failure carries every level (outer.inner);
        // the leaf field name is what the caller needs to act on. Confirms that
        // extractFieldName's reduce-to-last yields the leaf, not "request" or "outer".
        var mapper = new ObjectMapper();
        var cause = assertThrows(
                InvalidFormatException.class,
                () -> mapper.readValue(
                        "{\"outer\":{\"status\":\"PROPOSED\"}}", NestedControlTestConclusionRequest.class));
        var ex = new HttpMessageNotReadableException("bad nested enum", cause);

        var response = handler.handleHttpMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().detail()).containsEntry("field", "status");
    }

    @Test
    void handleMethodArgumentTypeMismatch_returnsBadRequest() {
        var ex = new MethodArgumentTypeMismatchException("abc", String.class, "id", null, null);

        var response = handler.handleMethodArgumentTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("bad_request");
        assertThat(response.getBody().error().message()).isEqualTo("Invalid value for parameter 'id'");
    }

    @Test
    void handleMethodNotSupported_returnsMethodNotAllowed() {
        var ex = new HttpRequestMethodNotSupportedException("POST");

        var response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("method_not_allowed");
        assertThat(response.getBody().error().message()).isEqualTo("Request method 'POST' is not supported");
    }

    @Test
    void handleMissingServletRequestParameter_returnsBadRequest() {
        var ex = new MissingServletRequestParameterException("page", "int");

        var response = handler.handleMissingServletRequestParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("bad_request");
        assertThat(response.getBody().error().message()).isEqualTo("Missing required parameter 'page'");
    }

    @Test
    void handleMissingServletRequestPart_returnsBadRequest() {
        var ex = new MissingServletRequestPartException("file");

        var response = handler.handleMissingServletRequestPart(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("bad_request");
        assertThat(response.getBody().error().message()).isEqualTo("Missing required part 'file'");
    }

    @Test
    void handleNoResourceFound_returnsNotFound() {
        // Spring throws NoResourceFoundException for unmapped paths in 3.2+. Without
        // a dedicated handler it falls through to handleGeneric and surfaces
        // 500 internal_error (#828: muddied #821 diagnosis of the threat-model 500
        // by hiding "this image doesn't have the threat-model controllers" behind
        // the same envelope a real server bug would emit).
        var ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/nonexistent");

        var response = handler.handleNoResourceFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("not_found");
        assertThat(response.getBody().error().message()).isEqualTo("Resource not found");
        // Resource path is not echoed in the response body — consistent with the
        // other handlers' message style and avoids reflecting client-controlled
        // bytes back through the error envelope.
        assertThat(response.getBody().error().detail()).isNull();
    }

    @Test
    void handleGeneric_returnsInternalServerError() {
        var ex = new RuntimeException("something broke");

        var response = handler.handleGeneric(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("internal_error");
        assertThat(response.getBody().error().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void allHandlers_returnConsistentErrorEnvelope() {
        ErrorResponse r1 = handler.handleHttpMessageNotReadable(new HttpMessageNotReadableException("x"))
                .getBody();
        ErrorResponse r2 = handler.handleGeneric(new RuntimeException("x")).getBody();

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r1.error().code()).isNotBlank();
        assertThat(r2.error().code()).isNotBlank();
        assertThat(r1.error().message()).isNotBlank();
        assertThat(r2.error().message()).isNotBlank();
    }

    @Test
    void handleConflict_omitsDetailWhenExceptionDetailIsEmpty() {
        var ex = new ConflictException("Already exists");

        var response = handler.handleConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        // Empty detail must be omitted from the wire envelope, not serialized as `{}`,
        // so legacy single-arg ConflictException sites do not regress after the
        // detail-aware envelope was added.
        assertThat(response.getBody().error().detail()).isNull();
    }

    @Test
    void handleConflict_includesDetailWhenExceptionCarriesDetail() {
        Map<String, Serializable> detail = new LinkedHashMap<>();
        detail.put("threatModelUid", "TM-001");
        detail.put("assetUids", new java.util.ArrayList<>(List.of("ASSET-001")));
        var ex = new ConflictException("Threat model referenced", "threat_model_referenced", detail);

        var response = handler.handleConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("threat_model_referenced");
        assertThat(response.getBody().error().detail()).containsEntry("threatModelUid", "TM-001");
        assertThat(response.getBody().error().detail()).containsKey("assetUids");
    }

    @Test
    void handleValidation_omitsDetailWhenExceptionDetailIsEmpty() {
        var ex = new DomainValidationException("Title must not be blank");

        var response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        // Same envelope contract as handleConflict: empty detail must be omitted
        // so legacy single-arg DomainValidationException sites do not serialize
        // `detail: {}` for the much larger 422 surface.
        assertThat(response.getBody().error().detail()).isNull();
    }

    @Test
    void handleValidation_includesDetailWhenExceptionCarriesDetail() {
        Map<String, Serializable> detail = new LinkedHashMap<>();
        detail.put("field", "title");
        var ex = new DomainValidationException("Title must not be blank", "validation_error", detail);

        var response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("validation_error");
        assertThat(response.getBody().error().detail()).containsEntry("field", "title");
    }

    @Test
    void handleDataIntegrityViolation_returns409ResourceConflict() {
        // Multiple services pair preflight existsBy checks with database UNIQUE
        // constraints; under concurrent writes only the DB constraint catches
        // the loser. Without this handler the loser surfaced as a generic 500
        // and the legitimate race was hidden behind "internal_error". The
        // translation must NOT echo the constraint name or the conflicting
        // payload — constraint names are an implementation detail and the
        // original request may contain user-controlled tokens.
        var ex = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement; constraint [uq_test_case_gherkin_test_case]");

        var response = handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("resource_conflict");
        // Stable, generic message — no constraint name leak.
        assertThat(response.getBody().error().message()).doesNotContain("uq_test_case_gherkin_test_case");
        assertThat(response.getBody().error().message()).doesNotContain("constraint");
    }

    private record ControlTestConclusionRequest(ControlTestConclusion status) {}

    private record NestedControlTestConclusionRequest(ControlTestConclusionRequest outer) {}
}
