package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.test.oracle.AsOfShapedNameMatcher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-084 §5 structural guard: the generated OpenAPI spec must declare no as-of-shaped request
 * parameter or request-body field. {@link com.keplerops.groundcontrol.domain.audit.service
 * .AsOfRevisionResolver} is the one resolution rule; a controller accepting a caller-supplied
 * {@code asOf}/{@code as_of}-shaped value (query param, path param, or a request-body DTO field)
 * would let a future endpoint reimplement or bypass revision-time semantics per ADR-084 §5's
 * "per-service divergence from this definition is a defect."
 *
 * <p>Runs against the real Springdoc-generated spec (same MockMvc path as
 * {@code McpOpenApiContractSpecTest}), not a hand-maintained inventory, so a future controller
 * cannot silently reintroduce this surface.
 */
@AutoConfigureMockMvc
@Transactional
class OpenApiAsOfParameterGuardTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiSpecDeclaresNoAsOfShapedParameterOrRequestBodyField() throws Exception {
        var result = mockMvc.perform(get("/api/openapi.json"))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode spec = new ObjectMapper().readTree(json);

        List<String> violations = new ArrayList<>();
        collectParameterViolations(spec.path("paths"), violations);
        collectSchemaPropertyViolations(spec.path("components").path("schemas"), violations);

        assertThat(violations)
                .as("ADR-084 §5: no request surface may declare an as-of-shaped value; "
                        + "AsOfRevisionResolver is the one resolution rule")
                .isEmpty();
    }

    private static void collectParameterViolations(JsonNode paths, List<String> violations) {
        var pathFields = paths.fields();
        while (pathFields.hasNext()) {
            var pathEntry = pathFields.next();
            String path = pathEntry.getKey();
            var operationFields = pathEntry.getValue().fields();
            while (operationFields.hasNext()) {
                var operationEntry = operationFields.next();
                String method = operationEntry.getKey();
                for (JsonNode parameter : operationEntry.getValue().path("parameters")) {
                    String name = parameter.path("name").asText("");
                    if (AsOfShapedNameMatcher.matches(name)) {
                        violations.add(
                                method.toUpperCase(java.util.Locale.ROOT) + " " + path + " parameter '" + name + "'");
                    }
                }
            }
        }
    }

    private static void collectSchemaPropertyViolations(JsonNode schemas, List<String> violations) {
        var schemaFields = schemas.fields();
        while (schemaFields.hasNext()) {
            var schemaEntry = schemaFields.next();
            String schemaName = schemaEntry.getKey();
            var propertyNames = schemaEntry.getValue().path("properties").fieldNames();
            while (propertyNames.hasNext()) {
                String propertyName = propertyNames.next();
                if (AsOfShapedNameMatcher.matches(propertyName)) {
                    violations.add("schema " + schemaName + " property '" + propertyName + "'");
                }
            }
        }
    }
}
