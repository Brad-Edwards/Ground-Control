package com.keplerops.groundcontrol.integration;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Split from AuditHistoryIntegrationTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@AutoConfigureMockMvc
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditHistoryIntegrationTimeline_longStatementTruncatedByDefaultTest extends BaseIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private UUID requirementId;
    private UUID targetRequirementId;
    private UUID relationId;
    private UUID traceabilityLinkId;

    @AfterAll
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM traceability_link_audit WHERE id IN "
                    + "(SELECT id FROM traceability_link WHERE requirement_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%'))");
            stmt.executeUpdate("DELETE FROM traceability_link WHERE requirement_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%')");
            stmt.executeUpdate("DELETE FROM requirement_relation_audit WHERE id IN "
                    + "(SELECT id FROM requirement_relation WHERE source_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%'))");
            stmt.executeUpdate("DELETE FROM requirement_relation WHERE source_id IN "
                    + "(SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%')");
            stmt.executeUpdate(
                    "DELETE FROM requirement_audit WHERE id IN (SELECT id FROM requirement WHERE uid LIKE 'AUDIT-%')");
            stmt.executeUpdate("DELETE FROM requirement WHERE uid LIKE 'AUDIT-%'");
        }
    }

    @Test
    @Order(35)
    void timeline_longStatementTruncatedByDefault() throws Exception {
        // Create a requirement with a long statement
        var longStatement = "X".repeat(300);
        var createBody = Map.of(
                "uid", "AUDIT-LONG",
                "title", "Long statement req",
                "statement", longStatement,
                "requirementType", "FUNCTIONAL",
                "priority", "MUST");
        var createResult = mockMvc.perform(post("/api/v1/requirements")
                        .header("X-Actor", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn();
        var longReqId = objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("id")
                .asText();

        // Default (no expand) - change value should be truncated to 200 chars, truncated=true
        mockMvc.perform(get("/api/v1/requirements/" + longReqId + "/timeline").param("changeCategory", "REQUIREMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changes.statement.newValue", is(longStatement.substring(0, 200))))
                .andExpect(jsonPath("$[0].changes.statement.truncated", is(true)))
                .andExpect(jsonPath("$[0].truncated", is(true)));

        // With expand=true - full value, truncated=false
        mockMvc.perform(get("/api/v1/requirements/" + longReqId + "/timeline")
                        .param("changeCategory", "REQUIREMENT")
                        .param("expand", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changes.statement.newValue", is(longStatement)))
                .andExpect(jsonPath("$[0].changes.statement.truncated", is(false)))
                .andExpect(jsonPath("$[0].truncated", is(false)));
    }
}
