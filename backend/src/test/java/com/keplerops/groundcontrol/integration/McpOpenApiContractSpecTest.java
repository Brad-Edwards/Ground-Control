package com.keplerops.groundcontrol.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures the Springdoc-generated OpenAPI spec via MockMvc and writes it to both
 * {@code build/contract/openapi.json} for downstream MCP contract testing and
 * {@code ../contracts/openapi/openapi.json} as the committed contract artifact
 * (issues #1106/#1275, ADR-034/ADR-082).
 *
 * <p>The test profile already enables {@code groundcontrol.security.openapi-public=true}
 * and {@code groundcontrol.security.enabled=false}, so GET /api/openapi.json succeeds
 * without authentication in this profile. The test additionally asserts that key GRC
 * schema names are present so a silent spec regression fails the gate immediately.
 */
@AutoConfigureMockMvc
@Transactional
class McpOpenApiContractSpecTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void capturesOpenApiSpec_andWritesToContractFile() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/openapi.json"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        if (json == null || json.isBlank()) {
            throw new AssertionError("OpenAPI spec response was empty");
        }

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode spec = mapper.readTree(json);

        // Assert key GRC schemas are present so a silent spec regression fails fast.
        JsonNode schemas = spec.path("components").path("schemas");
        assertSchemaPresent(schemas, "AuditRequest");
        assertSchemaPresent(schemas, "ThreatModelRequest");
        assertSchemaPresent(schemas, "RiskScenarioRequest");
        assertSchemaPresent(schemas, "FindingRequest");
        assertSchemaPresent(schemas, "EvidenceArtifactRequest");

        // Write the pretty-printed spec to build/contract/openapi.json
        // (relative to the backend module directory, which is the test working dir)
        // and to ../contracts/openapi/openapi.json, the committed contract surface.
        File outputFile = new File("build/contract/openapi.json");
        outputFile.getParentFile().mkdirs();
        mapper.writeValue(outputFile, spec);

        File committedContractFile = new File("../contracts/openapi/openapi.json");
        committedContractFile.getParentFile().mkdirs();
        mapper.writeValue(committedContractFile, spec);
    }

    private static void assertSchemaPresent(JsonNode schemas, String schemaName) {
        if (!schemas.has(schemaName)) {
            throw new AssertionError("OpenAPI spec missing expected GRC schema: " + schemaName + ". Available schemas: "
                    + schemas.fieldNames());
        }
    }
}
