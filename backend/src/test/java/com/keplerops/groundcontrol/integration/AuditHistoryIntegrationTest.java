package com.keplerops.groundcontrol.integration;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * Audit-history <em>controller</em> coverage (history / timeline / diff endpoints). Runs under the
 * security-disabled {@code test} profile, where {@code X-Actor} is the legacy convenience for
 * setting a readable audit actor (see {@code ActorFilter}). Per ADR-033 §5 this is a controller
 * slice test and is <em>not</em> the audit-actor-provenance evidence for issue #431 — see
 * {@link AuditActorProvenanceIntegrationTest} for the security-enabled provenance contracts.
 */
@AutoConfigureMockMvc
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditHistoryIntegrationTest extends BaseIntegrationTest {

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
    @Order(1)
    void createAndUpdateWithActor_thenHistoryRecordsActor() throws Exception {
        // Create with X-Actor header
        var createBody = Map.of(
                "uid", "AUDIT-001",
                "title", "Audit test requirement",
                "statement", "Created for audit history test",
                "requirementType", "FUNCTIONAL",
                "priority", "MUST");
        var createResult = mockMvc.perform(post("/api/v1/requirements")
                        .header("X-Actor", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn();
        requirementId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        // Update title with X-Actor header
        var updateBody = Map.of(
                "uid", "AUDIT-001",
                "title", "Updated audit title",
                "statement", "Created for audit history test");
        mockMvc.perform(put("/api/v1/requirements/" + requirementId)
                        .header("X-Actor", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk());

        // Get history — expect 2 revisions with actor = "test-user"
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                .andExpect(jsonPath("$[0].timestamp", notNullValue()))
                .andExpect(jsonPath("$[0].actor", is("test-user")))
                .andExpect(jsonPath("$[0].snapshot.uid", is("AUDIT-001")))
                .andExpect(jsonPath("$[0].snapshot.title", is("Audit test requirement")))
                .andExpect(jsonPath("$[1].revisionType", is("MOD")))
                .andExpect(jsonPath("$[1].actor", is("test-user")))
                .andExpect(jsonPath("$[1].snapshot.title", is("Updated audit title")));
    }

    @Test
    @Order(2)
    void createWithoutActorHeader_defaultsToAnonymous() throws Exception {
        var createBody = Map.of(
                "uid", "AUDIT-002",
                "title", "Anonymous audit test",
                "statement", "Created without X-Actor header",
                "requirementType", "FUNCTIONAL",
                "priority", "MUST");
        var createResult = mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn();
        var anonId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        mockMvc.perform(get("/api/v1/requirements/" + anonId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor", is("anonymous")));
    }

    @Test
    @Order(3)
    void historyForNonexistentRequirement_returns404() throws Exception {
        var fakeId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/requirements/" + fakeId + "/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    @Order(4)
    void createRelation_thenHistoryRecordsRevision() throws Exception {
        // Create target requirement
        var targetBody = Map.of(
                "uid", "AUDIT-003",
                "title", "Relation target",
                "statement", "Target for relation history test",
                "requirementType", "FUNCTIONAL",
                "priority", "MUST");
        var targetResult = mockMvc.perform(post("/api/v1/requirements")
                        .header("X-Actor", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(targetBody)))
                .andExpect(status().isCreated())
                .andReturn();
        targetRequirementId = UUID.fromString(objectMapper
                .readTree(targetResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        // Create relation
        var relationBody = Map.of("targetId", targetRequirementId.toString(), "relationType", "DEPENDS_ON");
        var relationResult = mockMvc.perform(post("/api/v1/requirements/" + requirementId + "/relations")
                        .header("X-Actor", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(relationBody)))
                .andExpect(status().isCreated())
                .andReturn();
        relationId = UUID.fromString(objectMapper
                .readTree(relationResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        // Get relation history — expect ADD revision with actor
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/relations/" + relationId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                .andExpect(jsonPath("$[0].actor", is("test-user")))
                .andExpect(jsonPath("$[0].timestamp", notNullValue()))
                .andExpect(jsonPath("$[0].snapshot.sourceId", is(requirementId.toString())))
                .andExpect(jsonPath("$[0].snapshot.targetId", is(targetRequirementId.toString())))
                .andExpect(jsonPath("$[0].snapshot.relationType", is("DEPENDS_ON")));
    }

    @Test
    @Order(5)
    void createTraceabilityLink_thenHistoryRecordsRevision() throws Exception {
        // Transition requirement to ACTIVE (required for IMPLEMENTS links)
        mockMvc.perform(post("/api/v1/requirements/" + requirementId + "/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isOk());

        // Create traceability link
        var linkBody = Map.of(
                "artifactType", "CODE_FILE",
                "artifactIdentifier", "src/main/java/Example.java",
                "artifactTitle", "Example file",
                "linkType", "IMPLEMENTS");
        var linkResult = mockMvc.perform(post("/api/v1/requirements/" + requirementId + "/traceability")
                        .header("X-Actor", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkBody)))
                .andExpect(status().isCreated())
                .andReturn();
        traceabilityLinkId = UUID.fromString(objectMapper
                .readTree(linkResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        // Get traceability link history — expect ADD revision with actor
        mockMvc.perform(get(
                        "/api/v1/requirements/" + requirementId + "/traceability/" + traceabilityLinkId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                .andExpect(jsonPath("$[0].actor", is("test-user")))
                .andExpect(jsonPath("$[0].timestamp", notNullValue()))
                .andExpect(jsonPath("$[0].snapshot.requirementId", is(requirementId.toString())))
                .andExpect(jsonPath("$[0].snapshot.artifactType", is("CODE_FILE")))
                .andExpect(jsonPath("$[0].snapshot.artifactIdentifier", is("src/main/java/Example.java")));
    }

    // --- Timeline endpoint tests (builds on data from steps 1-5) ---

    @Test
    @Order(6)
    void timeline_mergesAllChangeTypes() throws Exception {
        // requirementId has: 2 requirement revisions (ADD + MOD), 1 relation, 1 traceability link
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$[0].timestamp", notNullValue()))
                .andExpect(jsonPath("$[0].actor", is("test-user")))
                .andExpect(jsonPath("$[0].changeCategory", notNullValue()))
                .andExpect(jsonPath("$[0].entityId", notNullValue()))
                .andExpect(jsonPath("$[0].snapshot", notNullValue()));
    }

    @Test
    @Order(7)
    void timeline_filterByChangeCategory() throws Exception {
        // Filter to only REQUIREMENT changes — should get 3 (ADD + title MOD + status transition MOD)
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "REQUIREMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].changeCategory", is("REQUIREMENT")))
                .andExpect(jsonPath("$[1].changeCategory", is("REQUIREMENT")))
                .andExpect(jsonPath("$[2].changeCategory", is("REQUIREMENT")));

        // Filter to only RELATION changes
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "RELATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].changeCategory", is("RELATION")));

        // Filter to only TRACEABILITY_LINK changes
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "TRACEABILITY_LINK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].changeCategory", is("TRACEABILITY_LINK")));
    }

    @Test
    @Order(8)
    void timeline_computesFieldDiffs() throws Exception {
        // MOD revision should have changes computed (title was updated in step 1)
        // $[0] is the status transition (newest), $[1] is the title update
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "REQUIREMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].revisionType", is("MOD")))
                .andExpect(jsonPath("$[1].changes.title.oldValue", is("Audit test requirement")))
                .andExpect(jsonPath("$[1].changes.title.newValue", is("Updated audit title")));
    }

    @Test
    @Order(9)
    void timeline_invalidChangeCategory_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(10)
    void timeline_nonexistentRequirement_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + UUID.randomUUID() + "/timeline"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    @Order(11)
    void timeline_paginationLimitAndOffset() throws Exception {
        // Limit to 2 entries
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Offset past all entries
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("offset", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @Order(12)
    void relationHistory_resolvesViaTargetRequirement_returns200() throws Exception {
        // Relation ownership is source-OR-target, consistent with GET /requirements/{id}/relations.
        // The relation created in step 4 was AUDIT-001 -> AUDIT-003; here we look it up via the
        // target requirement and expect the same ADD revision, not a 404.
        mockMvc.perform(get("/api/v1/requirements/" + targetRequirementId + "/relations/" + relationId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                .andExpect(jsonPath("$[0].snapshot.sourceId", is(requirementId.toString())))
                .andExpect(jsonPath("$[0].snapshot.targetId", is(targetRequirementId.toString())));
    }

    // --- Version diff endpoint tests (builds on data from steps 1-5) ---

    @Test
    @Order(20)
    void diff_showsFieldChangesAndAddedEntities() throws Exception {
        // Get history to discover revision numbers
        var historyResult = mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/history"))
                .andExpect(status().isOk())
                .andReturn();
        var historyJson = objectMapper.readTree(historyResult.getResponse().getContentAsString());
        int firstRevision = historyJson.get(0).get("revisionNumber").asInt();
        int lastRevision =
                historyJson.get(historyJson.size() - 1).get("revisionNumber").asInt();

        // Diff between first and last revision — should show field changes,
        // relation added, and traceability link added
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/diff")
                        .param("fromRevision", String.valueOf(firstRevision))
                        .param("toRevision", String.valueOf(lastRevision + 100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementId", is(requirementId.toString())))
                .andExpect(jsonPath("$.fieldChanges.title.oldValue", is("Audit test requirement")))
                .andExpect(jsonPath("$.fieldChanges.title.newValue", is("Updated audit title")))
                .andExpect(jsonPath("$.relationChanges[0].changeType", is("ADDED")))
                .andExpect(jsonPath("$.relationChanges[0].snapshot.relationType", is("DEPENDS_ON")))
                .andExpect(jsonPath("$.traceabilityLinkChanges[0].changeType", is("ADDED")))
                .andExpect(jsonPath("$.traceabilityLinkChanges[0].snapshot.artifactType", is("CODE_FILE")));
    }

    @Test
    @Order(21)
    void diff_emptyWhenNoChangesBetweenRevisions() throws Exception {
        // The timeline spans all categories (requirement, relation, traceability link), so its
        // highest revisionNumber is the last revision that touched this requirement in any way -
        // unlike /history, which only reports requirement field revisions and would miss later
        // relation/link operations.
        var timelineResult = mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline"))
                .andExpect(status().isOk())
                .andReturn();
        var timelineJson = objectMapper.readTree(timelineResult.getResponse().getContentAsString());
        int maxRevision = 0;
        for (var entry : timelineJson) {
            maxRevision = Math.max(maxRevision, entry.get("revisionNumber").asInt());
        }

        // Diffing the latest revision against a higher, non-existent revision yields no changes:
        // the requirement, its relations, and its traceability links are all unchanged after
        // maxRevision, so all three change collections must be empty (this also guards against
        // phantom ADDED/MODIFIED entries from the snapshot resolution when toRevision exceeds the
        // highest real revision).
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/diff")
                        .param("fromRevision", String.valueOf(maxRevision))
                        .param("toRevision", String.valueOf(maxRevision + 100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldChanges").isEmpty())
                .andExpect(jsonPath("$.relationChanges").isEmpty())
                .andExpect(jsonPath("$.traceabilityLinkChanges").isEmpty());
    }

    @Test
    @Order(22)
    void diff_nonexistentRequirement_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + UUID.randomUUID() + "/diff")
                        .param("fromRevision", "1")
                        .param("toRevision", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    @Order(23)
    void diff_invalidRevisionOrder_returns422() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/diff")
                        .param("fromRevision", "10")
                        .param("toRevision", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    @Order(24)
    void diff_missingParams_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/diff")).andExpect(status().isBadRequest());
    }

    // --- ADD/MOD/DEL diffs on timeline and history ---

    @Test
    @Order(30)
    void history_addRevision_hasAdditionDiff() throws Exception {
        // AUDIT-001 was created in step 1 — its first revision should carry (null, value) diffs
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                .andExpect(jsonPath("$[0].changes.title.oldValue").doesNotExist())
                .andExpect(jsonPath("$[0].changes.title.newValue", is("Audit test requirement")));
    }

    @Test
    @Order(31)
    void history_modRevision_hasFieldDiff() throws Exception {
        // Second revision (title MOD) should carry old vs new
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].revisionType", is("MOD")))
                .andExpect(jsonPath("$[1].changes.title.oldValue", is("Audit test requirement")))
                .andExpect(jsonPath("$[1].changes.title.newValue", is("Updated audit title")));
    }

    @Test
    @Order(32)
    void timeline_addEntry_hasAdditionDiff() throws Exception {
        // Relation ADD event should carry (null, value) diffs
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "RELATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                .andExpect(jsonPath("$[0].changes.relationType.oldValue").doesNotExist())
                .andExpect(jsonPath("$[0].changes.relationType.newValue", is("DEPENDS_ON")));
    }

    @Test
    @Order(33)
    void timeline_traceabilityLinkAdd_hasAdditionDiff() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "TRACEABILITY_LINK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                .andExpect(jsonPath("$[0].changes.artifactType.oldValue").doesNotExist())
                .andExpect(jsonPath("$[0].changes.artifactType.newValue", is("CODE_FILE")));
    }

    @Test
    @Order(34)
    void timeline_statusTransition_showsStatusFieldDiff() throws Exception {
        // Status was transitioned from DRAFT to ACTIVE in step 5
        mockMvc.perform(get("/api/v1/requirements/" + requirementId + "/timeline")
                        .param("changeCategory", "REQUIREMENT"))
                .andExpect(status().isOk())
                // Status transition is the newest entry (first in reversed-chronological order)
                .andExpect(jsonPath("$[0].revisionType", is("MOD")))
                .andExpect(jsonPath("$[0].changes.status.oldValue", is("DRAFT")))
                .andExpect(jsonPath("$[0].changes.status.newValue", is("ACTIVE")));
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
