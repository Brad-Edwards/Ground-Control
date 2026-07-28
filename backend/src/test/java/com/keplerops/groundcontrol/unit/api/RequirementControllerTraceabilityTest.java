package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.api.requirements.RequirementController;
import com.keplerops.groundcontrol.domain.exception.AuthenticationException;
import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.GroundControlException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.service.AuditService;
import com.keplerops.groundcontrol.domain.requirements.service.BulkTransitionResult;
import com.keplerops.groundcontrol.domain.requirements.service.CloneRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.CreateTraceabilityLinkCommand;
import com.keplerops.groundcontrol.domain.requirements.service.FieldChange;
import com.keplerops.groundcontrol.domain.requirements.service.RelationRevision;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementRevision;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Split from RequirementControllerTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RequirementController.class)
class RequirementControllerTraceabilityTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RequirementService requirementService;

    @MockitoBean
    private TraceabilityService traceabilityService;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
    }

    private static Requirement createRequirement(String uid) {
        var req = new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", UUID.randomUUID());
        setField(req, "createdAt", Instant.now());
        setField(req, "updatedAt", Instant.now());
        return req;
    }

    private static RequirementRelation createRelation(Requirement source, Requirement target) {
        var rel = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
        setField(rel, "id", UUID.randomUUID());
        setField(rel, "createdAt", Instant.now());
        return rel;
    }

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
    }

    @Nested
    class Traceability {

        private static TraceabilityLink createLink(Requirement req) {
            var link = new TraceabilityLink(req, ArtifactType.GITHUB_ISSUE, "GH-123", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            setField(link, "createdAt", Instant.now());
            setField(link, "updatedAt", Instant.now());
            return link;
        }

        @Test
        void getLinks_returns200() throws Exception {
            var req = createRequirement("REQ-001");
            var link = createLink(req);
            when(traceabilityService.getLinksForRequirement(req.getId())).thenReturn(List.of(link));

            mockMvc.perform(get("/api/v1/requirements/" + req.getId() + "/traceability"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].artifactType", is("GITHUB_ISSUE")))
                    .andExpect(jsonPath("$[0].artifactIdentifier", is("GH-123")))
                    .andExpect(jsonPath("$[0].linkType", is("IMPLEMENTS")));
        }

        @Test
        void createLink_returns201() throws Exception {
            var req = createRequirement("REQ-001");
            var link = createLink(req);
            when(traceabilityService.createLink(eq(req.getId()), any(CreateTraceabilityLinkCommand.class)))
                    .thenReturn(link);

            mockMvc.perform(post("/api/v1/requirements/" + req.getId() + "/traceability")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "artifactType", "GITHUB_ISSUE",
                                    "artifactIdentifier", "GH-123",
                                    "linkType", "IMPLEMENTS"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.artifactType", is("GITHUB_ISSUE")))
                    .andExpect(jsonPath("$.artifactIdentifier", is("GH-123")))
                    .andExpect(jsonPath("$.linkType", is("IMPLEMENTS")));
        }

        @Test
        void deleteLink_returns204() throws Exception {
            var reqId = UUID.randomUUID();
            var linkId = UUID.randomUUID();
            doNothing().when(traceabilityService).deleteLink(reqId, linkId);

            mockMvc.perform(delete("/api/v1/requirements/" + reqId + "/traceability/" + linkId))
                    .andExpect(status().isNoContent());
        }

        @Test
        void deleteLink_mismatchedRequirement_returns404() throws Exception {
            var wrongReqId = UUID.randomUUID();
            var linkId = UUID.randomUUID();
            doThrow(new NotFoundException("Traceability link not found: " + linkId))
                    .when(traceabilityService)
                    .deleteLink(wrongReqId, linkId);

            mockMvc.perform(delete("/api/v1/requirements/" + wrongReqId + "/traceability/" + linkId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")))
                    .andExpect(jsonPath("$.error.message", is("Traceability link not found: " + linkId)));
        }

        @Test
        void findByArtifact_returnsMatchingLinks() throws Exception {
            var req = createRequirement("REQ-001");
            var link = new TraceabilityLink(req, ArtifactType.CODE_FILE, "backend/src/Main.java", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            setField(link, "createdAt", Instant.now());
            setField(link, "updatedAt", Instant.now());
            when(traceabilityService.findByArtifact(ArtifactType.CODE_FILE, "backend/src/Main.java", PROJECT_ID))
                    .thenReturn(List.of(link));

            mockMvc.perform(get("/api/v1/requirements/traceability/by-artifact")
                            .param("artifactType", "CODE_FILE")
                            .param("artifactIdentifier", "backend/src/Main.java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].artifactType", is("CODE_FILE")))
                    .andExpect(jsonPath("$[0].artifactIdentifier", is("backend/src/Main.java")))
                    .andExpect(jsonPath("$[0].linkType", is("IMPLEMENTS")));
        }

        @Test
        void findByArtifact_returnsEmptyWhenNoMatch() throws Exception {
            when(traceabilityService.findByArtifact(ArtifactType.CODE_FILE, "nonexistent.java", PROJECT_ID))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/requirements/traceability/by-artifact")
                            .param("artifactType", "CODE_FILE")
                            .param("artifactIdentifier", "nonexistent.java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", is(0)));
        }

        @Test
        void findByArtifact_withProjectParam_forwardsResolvedProjectId() throws Exception {
            var req = createRequirement("REQ-001");
            var link = new TraceabilityLink(req, ArtifactType.GITHUB_ISSUE, "42", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            setField(link, "createdAt", Instant.now());
            setField(link, "updatedAt", Instant.now());
            when(traceabilityService.findByArtifact(ArtifactType.GITHUB_ISSUE, "42", PROJECT_ID))
                    .thenReturn(List.of(link));

            mockMvc.perform(get("/api/v1/requirements/traceability/by-artifact")
                            .param("artifactType", "GITHUB_ISSUE")
                            .param("artifactIdentifier", "42")
                            .param("project", "my-project"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].artifactType", is("GITHUB_ISSUE")));

            // verify the controller resolved the project slug and passed the UUID to the service
            verify(traceabilityService).findByArtifact(ArtifactType.GITHUB_ISSUE, "42", PROJECT_ID);
        }
    }

    @Nested
    class Clone {

        @Test
        void returns201WithClonedRequirement() throws Exception {
            var source = createRequirement("REQ-001");
            var clone = createRequirement("REQ-001-CLONE");
            when(requirementService.clone(eq(source.getId()), any(CloneRequirementCommand.class)))
                    .thenReturn(clone);

            mockMvc.perform(post("/api/v1/requirements/" + source.getId() + "/clone")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("newUid", "REQ-001-CLONE", "copyRelations", false))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.uid", is("REQ-001-CLONE")))
                    .andExpect(jsonPath("$.status", is("DRAFT")));
        }

        @Test
        void conflictUid_returns409() throws Exception {
            var sourceId = UUID.randomUUID();
            when(requirementService.clone(eq(sourceId), any(CloneRequirementCommand.class)))
                    .thenThrow(new ConflictException("Already exists"));

            mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/clone")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("newUid", "REQ-DUPLICATE", "copyRelations", false))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code", is("conflict")));
        }

        @Test
        void sourceNotFound_returns404() throws Exception {
            var sourceId = UUID.randomUUID();
            when(requirementService.clone(eq(sourceId), any(CloneRequirementCommand.class)))
                    .thenThrow(new NotFoundException("Not found"));

            mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/clone")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("newUid", "REQ-NEW", "copyRelations", false))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")));
        }

        @Test
        void blankNewUid_returns422() throws Exception {
            var sourceId = UUID.randomUUID();

            mockMvc.perform(post("/api/v1/requirements/" + sourceId + "/clone")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("newUid", "", "copyRelations", false))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("validation_error")));
        }
    }

    @Nested
    class BulkTransitionStatus {

        @Test
        void returns200WithResults() throws Exception {
            var req1 = createRequirement("REQ-001");
            setField(req1, "status", Status.ACTIVE);
            var req2 = createRequirement("REQ-002");
            setField(req2, "status", Status.ACTIVE);

            var result = new BulkTransitionResult(List.of(req1, req2), List.of());

            when(requirementService.bulkTransitionStatus(any(), eq(Status.ACTIVE)))
                    .thenReturn(result);

            mockMvc.perform(post("/api/v1/requirements/bulk/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("ids", List.of(req1.getId(), req2.getId()), "status", "ACTIVE"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRequested", is(2)))
                    .andExpect(jsonPath("$.totalSucceeded", is(2)))
                    .andExpect(jsonPath("$.totalFailed", is(0)))
                    .andExpect(jsonPath("$.succeeded[0].uid", is("REQ-001")))
                    .andExpect(jsonPath("$.succeeded[1].uid", is("REQ-002")));
        }

        @Test
        void emptyIdsReturns422() throws Exception {
            mockMvc.perform(post("/api/v1/requirements/bulk/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("ids", List.of(), "status", "ACTIVE"))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("validation_error")));
        }
    }

    @Nested
    class ExceptionHandlerCoverage {

        @Test
        void authenticationException_returns401() throws Exception {
            var id = UUID.randomUUID();
            when(requirementService.getById(id)).thenThrow(new AuthenticationException("unauthenticated"));

            mockMvc.perform(get("/api/v1/requirements/" + id))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code", is("authentication_error")));
        }

        @Test
        void authorizationException_returns403() throws Exception {
            var id = UUID.randomUUID();
            when(requirementService.getById(id)).thenThrow(new AuthorizationException("forbidden"));

            mockMvc.perform(get("/api/v1/requirements/" + id))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code", is("authorization_error")));
        }

        @Test
        void unhandledGroundControlException_returns500() throws Exception {
            var id = UUID.randomUUID();
            when(requirementService.getById(id)).thenThrow(new GroundControlException("unexpected", "internal_error"));

            mockMvc.perform(get("/api/v1/requirements/" + id))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code", is("internal_error")));
        }
    }

    @Nested
    class RequirementHistory {

        @Test
        void historyIncludesChangesWithNullOldValueForAdd() throws Exception {
            var req = createRequirement("REQ-001");
            var revision = new RequirementRevision(
                    1,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    "ADD",
                    "test-user",
                    null,
                    req,
                    Map.of("title", new FieldChange(null, "Title for REQ-001")));
            when(auditService.getRequirementHistory(req.getId())).thenReturn(List.of(revision));

            mockMvc.perform(get("/api/v1/requirements/" + req.getId() + "/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].changes.title.oldValue").doesNotExist())
                    .andExpect(jsonPath("$[0].changes.title.newValue", is("Title for REQ-001")))
                    .andExpect(jsonPath("$[0].truncated", is(false)));
        }
    }

    @Nested
    class RelationHistory {

        @Test
        void returns200WithRevisions() throws Exception {
            var source = createRequirement("REQ-001");
            var target = createRequirement("REQ-002");
            var rel = createRelation(source, target);
            var revision = new RelationRevision(1, Instant.now(), "ADD", "test-user", null, rel);
            when(auditService.getRelationHistory(source.getId(), rel.getId())).thenReturn(List.of(revision));

            mockMvc.perform(get("/api/v1/requirements/" + source.getId() + "/relations/" + rel.getId() + "/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", is(1)))
                    .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                    .andExpect(jsonPath("$[0].actor", is("test-user")))
                    .andExpect(jsonPath("$[0].snapshot.relationType", is("DEPENDS_ON")));
        }

        @Test
        void notFound_returns404() throws Exception {
            var reqId = UUID.randomUUID();
            var relId = UUID.randomUUID();
            when(auditService.getRelationHistory(reqId, relId)).thenThrow(new NotFoundException("Not found"));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/relations/" + relId + "/history"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")));
        }

        @Test
        void mismatchedRequirement_returns404() throws Exception {
            var wrongReqId = UUID.randomUUID();
            var relId = UUID.randomUUID();
            when(auditService.getRelationHistory(wrongReqId, relId))
                    .thenThrow(new NotFoundException("Relation not found: " + relId));

            mockMvc.perform(get("/api/v1/requirements/" + wrongReqId + "/relations/" + relId + "/history"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")))
                    .andExpect(jsonPath("$.error.message", is("Relation not found: " + relId)));
        }
    }
}
