package com.keplerops.groundcontrol.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class RequirementControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> validRequest(String uid) {
        return Map.of(
                "uid",
                uid,
                "title",
                "Title for " + uid,
                "statement",
                "Statement for " + uid,
                "requirementType",
                "FUNCTIONAL",
                "priority",
                "MUST");
    }

    private String createAndReturnId(String uid) throws Exception {
        var result = mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest(uid))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText();
    }

    @Test
    void createRequirement_returns201WithDraftStatus() throws Exception {
        mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest("REQ-C-001"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.uid", is("REQ-C-001")));
    }

    @Test
    void getById_returns200() throws Exception {
        var id = createAndReturnId("REQ-C-002");

        mockMvc.perform(get("/api/v1/requirements/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("REQ-C-002")));
    }

    @Test
    void getByUid_returns200() throws Exception {
        createAndReturnId("REQ-C-003");

        mockMvc.perform(get("/api/v1/requirements/uid/REQ-C-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("REQ-C-003")));
    }

    @Test
    void listRequirements_returns200WithPagination() throws Exception {
        createAndReturnId("REQ-C-004");

        mockMvc.perform(get("/api/v1/requirements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void updateRequirement_returns200() throws Exception {
        var id = createAndReturnId("REQ-C-005");

        var updateBody = Map.of(
                "title", "Updated Title",
                "statement", "Updated Statement");

        mockMvc.perform(put("/api/v1/requirements/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated Title")));
    }

    @Test
    void updateRequirement_partialUpdate_preservesOtherFields() throws Exception {
        var id = createAndReturnId("REQ-C-040");

        // Update only the title
        mockMvc.perform(put("/api/v1/requirements/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "New Title Only"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("New Title Only")))
                .andExpect(jsonPath("$.statement", is("Statement for REQ-C-040")))
                .andExpect(jsonPath("$.requirementType", is("FUNCTIONAL")))
                .andExpect(jsonPath("$.priority", is("MUST")));
    }

    @Test
    void transitionStatus_returns200() throws Exception {
        var id = createAndReturnId("REQ-C-006");

        mockMvc.perform(post("/api/v1/requirements/" + id + "/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    void archiveRequirement_returns200() throws Exception {
        var id = createAndReturnId("REQ-C-007");

        // Must be ACTIVE first
        mockMvc.perform(post("/api/v1/requirements/" + id + "/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/requirements/" + id + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ARCHIVED")))
                .andExpect(jsonPath("$.archivedAt", notNullValue()));
    }

    @Test
    void createRelation_returns201() throws Exception {
        var sourceId = createAndReturnId("REQ-C-008");
        var targetId = createAndReturnId("REQ-C-009");

        var body = Map.of("targetId", targetId, "relationType", "DEPENDS_ON");

        mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationType", is("DEPENDS_ON")));
    }

    @Test
    void getRelations_returns200() throws Exception {
        var id = createAndReturnId("REQ-C-010");

        mockMvc.perform(get("/api/v1/requirements/" + id + "/relations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getNonExistentId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    void invalidTransition_returns422() throws Exception {
        var id = createAndReturnId("REQ-C-011");

        mockMvc.perform(post("/api/v1/requirements/" + id + "/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ARCHIVED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("invalid_status_transition")));
    }

    @Test
    void blankTitle_returns422() throws Exception {
        var body = Map.of("uid", "REQ-C-012", "title", "", "statement", "Statement");

        mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void duplicateUid_returns409() throws Exception {
        createAndReturnId("REQ-C-013");

        mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest("REQ-C-013"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("conflict")));
    }

    @Test
    void filteredList_byPriority_returnsFiltered() throws Exception {
        // MUST priority (default from validRequest)
        createAndReturnId("REQ-C-030");

        // SHOULD priority
        var shouldReq = new HashMap<>(validRequest("REQ-C-031"));
        shouldReq.put("priority", "SHOULD");
        mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shouldReq)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/requirements").param("priority", "SHOULD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].uid", is("REQ-C-031")));
    }

    @Test
    void filteredList_byStatus_returnsFiltered() throws Exception {
        createAndReturnId("REQ-C-014");
        var activeId = createAndReturnId("REQ-C-015");
        mockMvc.perform(post("/api/v1/requirements/" + activeId + "/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/requirements").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].uid", is("REQ-C-015")));
    }

    @Test
    void filteredList_bySearch_returnsFiltered() throws Exception {
        createAndReturnId("REQ-C-016");

        mockMvc.perform(get("/api/v1/requirements").param("search", "REQ-C-016"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].uid", is("REQ-C-016")));
    }

    @Test
    void cloneRequirement_returns201WithCopiedFieldsAndRelations() throws Exception {
        var sourceId = createAndReturnId("REQ-CLONE-SRC");
        var targetId = createAndReturnId("REQ-CLONE-TGT");

        // Create a relation from source to target
        mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("targetId", targetId, "relationType", "DEPENDS_ON"))))
                .andExpect(status().isCreated());

        // Clone with relation copy
        var cloneResult = mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/clone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("newUid", "REQ-CLONE-NEW", "copyRelations", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid", is("REQ-CLONE-NEW")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.title", is("Title for REQ-CLONE-SRC")))
                .andReturn();

        // Verify cloned relations
        var cloneId = objectMapper
                .readTree(cloneResult.getResponse().getContentAsString())
                .get("id")
                .asText();
        mockMvc.perform(get("/api/v1/requirements/" + cloneId + "/relations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].relationType", is("DEPENDS_ON")));
    }

    @Test
    void cloneRequirement_withoutRelations_doesNotCopyRelations() throws Exception {
        var sourceId = createAndReturnId("REQ-CLONE-NR-SRC");
        var targetId = createAndReturnId("REQ-CLONE-NR-TGT");

        // Create a relation from source to target
        mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("targetId", targetId, "relationType", "DEPENDS_ON"))))
                .andExpect(status().isCreated());

        // Clone without relation copy
        var cloneResult = mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/clone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("newUid", "REQ-CLONE-NR-NEW", "copyRelations", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid", is("REQ-CLONE-NR-NEW")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.title", is("Title for REQ-CLONE-NR-SRC")))
                .andReturn();

        // Verify clone has no relations
        var cloneId = objectMapper
                .readTree(cloneResult.getResponse().getContentAsString())
                .get("id")
                .asText();
        mockMvc.perform(get("/api/v1/requirements/" + cloneId + "/relations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cloneRequirement_duplicateUid_returns409() throws Exception {
        var sourceId = createAndReturnId("REQ-CLONE-DUP-SRC");
        createAndReturnId("REQ-CLONE-DUP-EXISTING");

        mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/clone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("newUid", "REQ-CLONE-DUP-EXISTING", "copyRelations", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("conflict")));
    }

    @Test
    void cloneRequirement_nonExistentSource_returns404() throws Exception {
        var nonExistentId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/requirements/" + nonExistentId + "/clone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("newUid", "REQ-CLONE-GHOST", "copyRelations", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    void deleteRelation_returns204() throws Exception {
        var sourceId = createAndReturnId("REQ-C-017");
        var targetId = createAndReturnId("REQ-C-018");

        var body = Map.of("targetId", targetId, "relationType", "DEPENDS_ON");
        var result = mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        var relationId = objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(delete("/api/v1/requirements/" + sourceId + "/relations/" + relationId))
                .andExpect(status().isNoContent());
    }

    private String createRequirementWithWave(String uid, int wave) throws Exception {
        var body = new HashMap<String, Object>(validRequest(uid));
        body.put("wave", wave);
        var result = mockMvc.perform(post("/api/v1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText();
    }

    private void addTraceabilityLink(String requirementId, String artifactType, String artifactId, String linkType)
            throws Exception {
        var body = Map.of(
                "artifactType", artifactType,
                "artifactIdentifier", artifactId,
                "linkType", linkType);
        mockMvc.perform(post("/api/v1/requirements/" + requirementId + "/traceability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void matrix_returnsRequirementsWithGroupedLinks() throws Exception {
        var id1 = createRequirementWithWave("REQ-MX-001", 991);
        createRequirementWithWave("REQ-MX-002", 991);
        addTraceabilityLink(id1, "TEST", "backend/src/test/FooTest.java", "TESTS");

        mockMvc.perform(get("/api/v1/requirements/matrix").param("wave", "991").param("sort", "uid,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].requirement.uid", is("REQ-MX-001")))
                .andExpect(jsonPath("$.content[0].links", hasSize(1)))
                .andExpect(jsonPath("$.content[0].links[0].linkType", is("TESTS")))
                .andExpect(jsonPath("$.content[1].requirement.uid", is("REQ-MX-002")))
                .andExpect(jsonPath("$.content[1].links", hasSize(0)));
    }

    @Test
    void matrix_filtersByLinkTypeKeepingGapRequirements() throws Exception {
        var id1 = createRequirementWithWave("REQ-MX-010", 992);
        addTraceabilityLink(id1, "TEST", "backend/src/test/BarTest.java", "TESTS");

        // Filtering to DOCUMENTS excludes the TESTS link; the requirement still appears with an empty link list.
        mockMvc.perform(get("/api/v1/requirements/matrix").param("wave", "992").param("linkType", "DOCUMENTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].requirement.uid", is("REQ-MX-010")))
                .andExpect(jsonPath("$.content[0].links", hasSize(0)));
    }
}
