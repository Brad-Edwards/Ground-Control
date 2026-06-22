package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.api.requirements.RequirementController;
import com.keplerops.groundcontrol.domain.exception.AuthenticationException;
import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.GroundControlException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.service.AuditService;
import com.keplerops.groundcontrol.domain.requirements.service.BulkTransitionResult;
import com.keplerops.groundcontrol.domain.requirements.service.ChangeType;
import com.keplerops.groundcontrol.domain.requirements.service.CloneRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.CreateTraceabilityLinkCommand;
import com.keplerops.groundcontrol.domain.requirements.service.FieldChange;
import com.keplerops.groundcontrol.domain.requirements.service.RelationChange;
import com.keplerops.groundcontrol.domain.requirements.service.RelationRevision;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementFilter;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementRevision;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementVersionDiff;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementWithLinks;
import com.keplerops.groundcontrol.domain.requirements.service.TimelineEntry;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityLinkChange;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityLinkRevision;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.service.UpdateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.ChangeCategory;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RequirementController.class)
class RequirementControllerTest {

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
    class Create {

        @Test
        void returns201WithDraftStatus() throws Exception {
            var req = createRequirement("REQ-001");
            when(requirementService.create(any(CreateRequirementCommand.class))).thenReturn(req);

            mockMvc.perform(post("/api/v1/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "uid", "REQ-001",
                                    "title", "Test Title",
                                    "statement", "Test Statement",
                                    "requirementType", "FUNCTIONAL",
                                    "priority", "MUST"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", notNullValue()))
                    .andExpect(jsonPath("$.uid", is("REQ-001")))
                    .andExpect(jsonPath("$.status", is("DRAFT")));
        }

        @Test
        void blankTitle_returns422() throws Exception {
            mockMvc.perform(post("/api/v1/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("uid", "REQ-001", "title", "", "statement", "Stmt"))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("validation_error")));
        }

        @Test
        void duplicateUid_returns409() throws Exception {
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenThrow(new ConflictException("Already exists"));

            mockMvc.perform(post("/api/v1/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("uid", "REQ-001", "title", "Title", "statement", "Stmt"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code", is("conflict")));
        }
    }

    @Nested
    class GetById {

        @Test
        void returns200() throws Exception {
            var req = createRequirement("REQ-001");
            when(requirementService.getById(req.getId())).thenReturn(req);

            mockMvc.perform(get("/api/v1/requirements/" + req.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uid", is("REQ-001")));
        }

        @Test
        void notFound_returns404() throws Exception {
            var id = UUID.randomUUID();
            when(requirementService.getById(id)).thenThrow(new NotFoundException("Not found"));

            mockMvc.perform(get("/api/v1/requirements/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")));
        }
    }

    @Nested
    class GetByUid {

        @Test
        void returns200() throws Exception {
            var req = createRequirement("REQ-001");
            when(requirementService.getByUid(PROJECT_ID, "REQ-001")).thenReturn(req);

            mockMvc.perform(get("/api/v1/requirements/uid/REQ-001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uid", is("REQ-001")));
        }
    }

    @Nested
    class ListRequirements {

        @Test
        void returns200WithPagination() throws Exception {
            var req = createRequirement("REQ-001");
            when(requirementService.list(eq(PROJECT_ID), any(Pageable.class), any(RequirementFilter.class)))
                    .thenReturn(new PageImpl<>(List.of(req)));

            mockMvc.perform(get("/api/v1/requirements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].uid", is("REQ-001")));
        }

        @Test
        void returns200WithFilterParams() throws Exception {
            var req = createRequirement("REQ-001");
            when(requirementService.list(eq(PROJECT_ID), any(Pageable.class), any(RequirementFilter.class)))
                    .thenReturn(new PageImpl<>(List.of(req)));

            mockMvc.perform(get("/api/v1/requirements")
                            .param("status", "DRAFT")
                            .param("type", "FUNCTIONAL")
                            .param("priority", "MUST")
                            .param("wave", "1")
                            .param("search", "test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].uid", is("REQ-001")));
        }
    }

    @Nested
    class TraceabilityMatrixEndpoint {

        private TraceabilityLink linkFor(Requirement req) {
            var link = new TraceabilityLink(req, ArtifactType.CODE_FILE, "backend/src/Main.java", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            return link;
        }

        @Test
        void returns200WithRequirementsAndGroupedLinks() throws Exception {
            var req = createRequirement("REQ-MX-1");
            var row = new RequirementWithLinks(req, List.of(linkFor(req)));
            when(requirementService.getTraceabilityMatrix(
                            eq(PROJECT_ID), any(Pageable.class), any(RequirementFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(row)));

            mockMvc.perform(get("/api/v1/requirements/matrix"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].requirement.uid", is("REQ-MX-1")))
                    .andExpect(jsonPath("$.content[0].links[0].linkType", is("IMPLEMENTS")))
                    .andExpect(jsonPath(
                            "$.content[0].links[0].requirementId",
                            is(req.getId().toString())));
        }

        @Test
        void passesLinkTypeFilterToService() throws Exception {
            var req = createRequirement("REQ-MX-2");
            var row = new RequirementWithLinks(req, List.of(linkFor(req)));
            when(requirementService.getTraceabilityMatrix(
                            eq(PROJECT_ID), any(Pageable.class), any(RequirementFilter.class), eq(LinkType.IMPLEMENTS)))
                    .thenReturn(new PageImpl<>(List.of(row)));

            mockMvc.perform(get("/api/v1/requirements/matrix").param("linkType", "IMPLEMENTS"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].requirement.uid", is("REQ-MX-2")));
        }

        @Test
        void returns200WithEmptyContentWhenNoRequirements() throws Exception {
            when(requirementService.getTraceabilityMatrix(
                            eq(PROJECT_ID), any(Pageable.class), any(RequirementFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/v1/requirements/matrix"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)));
        }
    }

    @Nested
    class Update {

        @Test
        void returns200() throws Exception {
            var req = createRequirement("REQ-001");
            req.setTitle("Updated");
            when(requirementService.update(eq(req.getId()), any(UpdateRequirementCommand.class)))
                    .thenReturn(req);

            mockMvc.perform(put("/api/v1/requirements/" + req.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "Updated", "statement", "Stmt"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title", is("Updated")));
        }

        @Test
        void update_withBlankTitle_returns422() throws Exception {
            var id = UUID.randomUUID();

            mockMvc.perform(put("/api/v1/requirements/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", ""))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("validation_error")));
        }

        @Test
        void update_withPartialFields_returns200() throws Exception {
            var req = createRequirement("REQ-001");
            req.setWave(3);
            when(requirementService.update(eq(req.getId()), any(UpdateRequirementCommand.class)))
                    .thenReturn(req);

            mockMvc.perform(put("/api/v1/requirements/" + req.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("wave", 3))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.wave", is(3)));
        }
    }

    @Nested
    class TransitionStatus {

        @Test
        void returns200() throws Exception {
            var req = createRequirement("REQ-001");
            setField(req, "status", Status.ACTIVE);
            when(requirementService.transitionStatus(req.getId(), Status.ACTIVE))
                    .thenReturn(req);

            mockMvc.perform(post("/api/v1/requirements/" + req.getId() + "/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"ACTIVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("ACTIVE")));
        }

        @Test
        void invalidTransition_returns422() throws Exception {
            var id = UUID.randomUUID();
            when(requirementService.transitionStatus(id, Status.ARCHIVED))
                    .thenThrow(new DomainValidationException(
                            "Cannot transition",
                            "invalid_status_transition",
                            Map.of("current_status", "DRAFT", "target_status", "ARCHIVED")));

            mockMvc.perform(post("/api/v1/requirements/" + id + "/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"ARCHIVED\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("invalid_status_transition")));
        }
    }

    @Nested
    class Archive {

        @Test
        void returns200() throws Exception {
            var req = createRequirement("REQ-001");
            setField(req, "status", Status.ARCHIVED);
            setField(req, "archivedAt", Instant.now());
            when(requirementService.archive(req.getId())).thenReturn(req);

            mockMvc.perform(post("/api/v1/requirements/" + req.getId() + "/archive"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("ARCHIVED")))
                    .andExpect(jsonPath("$.archivedAt", notNullValue()));
        }
    }

    @Nested
    class Relations {

        @Test
        void createRelation_returns201() throws Exception {
            var source = createRequirement("REQ-001");
            var target = createRequirement("REQ-002");
            var rel = createRelation(source, target);
            when(requirementService.createRelation(source.getId(), target.getId(), RelationType.DEPENDS_ON))
                    .thenReturn(rel);

            mockMvc.perform(post("/api/v1/requirements/" + source.getId() + "/relations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("targetId", target.getId(), "relationType", "DEPENDS_ON"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.relationType", is("DEPENDS_ON")))
                    .andExpect(jsonPath("$.sourceUid", is("REQ-001")))
                    .andExpect(jsonPath("$.targetUid", is("REQ-002")));
        }

        @Test
        void getRelations_returns200() throws Exception {
            var source = createRequirement("REQ-001");
            var target = createRequirement("REQ-002");
            var rel = createRelation(source, target);
            when(requirementService.getRelations(source.getId())).thenReturn(List.of(rel));

            mockMvc.perform(get("/api/v1/requirements/" + source.getId() + "/relations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].relationType", is("DEPENDS_ON")));
        }
    }

    @Nested
    class DeleteRelation {

        @Test
        void returns204() throws Exception {
            var reqId = UUID.randomUUID();
            var relationId = UUID.randomUUID();
            doNothing().when(requirementService).deleteRelation(reqId, relationId);

            mockMvc.perform(delete("/api/v1/requirements/" + reqId + "/relations/" + relationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        void notFound_returns404() throws Exception {
            var reqId = UUID.randomUUID();
            var relationId = UUID.randomUUID();
            doThrow(new NotFoundException("Not found")).when(requirementService).deleteRelation(reqId, relationId);

            mockMvc.perform(delete("/api/v1/requirements/" + reqId + "/relations/" + relationId))
                    .andExpect(status().isNotFound());
        }

        @Test
        void mismatchedRequirement_returns404() throws Exception {
            var wrongReqId = UUID.randomUUID();
            var relationId = UUID.randomUUID();
            doThrow(new NotFoundException("Relation not found: " + relationId))
                    .when(requirementService)
                    .deleteRelation(wrongReqId, relationId);

            mockMvc.perform(delete("/api/v1/requirements/" + wrongReqId + "/relations/" + relationId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")))
                    .andExpect(jsonPath("$.error.message", is("Relation not found: " + relationId)));
        }
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
                    .andExpect(jsonPath("$.artifactType", is("GITHUB_ISSUE")));
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
            when(traceabilityService.findByArtifact(ArtifactType.CODE_FILE, "backend/src/Main.java"))
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
            when(traceabilityService.findByArtifact(ArtifactType.CODE_FILE, "nonexistent.java"))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/requirements/traceability/by-artifact")
                            .param("artifactType", "CODE_FILE")
                            .param("artifactIdentifier", "nonexistent.java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", is(0)));
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

    @Nested
    class Timeline {

        @Test
        void returns200WithEntries() throws Exception {
            var reqId = UUID.randomUUID();
            var entry = new TimelineEntry(
                    1,
                    "ADD",
                    Instant.parse("2026-03-21T04:00:00Z"),
                    "test-user",
                    null,
                    ChangeCategory.REQUIREMENT,
                    reqId,
                    Map.of("title", "My Requirement", "status", "DRAFT"),
                    Map.of());
            when(auditService.getRequirementTimeline(eq(reqId), any(), any(), any(), any(), eq(100), eq(0)))
                    .thenReturn(List.of(entry));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/timeline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", is(1)))
                    .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                    .andExpect(jsonPath("$[0].changeCategory", is("REQUIREMENT")))
                    .andExpect(jsonPath("$[0].actor", is("test-user")))
                    .andExpect(jsonPath("$[0].snapshot.title", is("My Requirement")));
        }

        @Test
        void returns200WithDiffs() throws Exception {
            var reqId = UUID.randomUUID();
            var changes = Map.of("title", new FieldChange("Old Title", "New Title"));
            var entry = new TimelineEntry(
                    2,
                    "MOD",
                    Instant.parse("2026-03-21T05:00:00Z"),
                    "test-user",
                    null,
                    ChangeCategory.REQUIREMENT,
                    reqId,
                    Map.of("title", "New Title", "status", "ACTIVE"),
                    changes);
            when(auditService.getRequirementTimeline(eq(reqId), any(), any(), any(), any(), eq(100), eq(0)))
                    .thenReturn(List.of(entry));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/timeline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].changes.title.oldValue", is("Old Title")))
                    .andExpect(jsonPath("$[0].changes.title.newValue", is("New Title")));
        }

        @Test
        void timelineDefaultTruncatesLongChangeValue() throws Exception {
            var reqId = UUID.randomUUID();
            var longValue = "A".repeat(300);
            var changes = Map.of("statement", new FieldChange("short", longValue));
            var entry = new TimelineEntry(
                    2,
                    "MOD",
                    Instant.parse("2026-03-21T05:00:00Z"),
                    "test-user",
                    null,
                    ChangeCategory.REQUIREMENT,
                    reqId,
                    Map.of("statement", longValue),
                    changes);
            when(auditService.getRequirementTimeline(eq(reqId), any(), any(), any(), any(), eq(100), eq(0)))
                    .thenReturn(List.of(entry));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/timeline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].changes.statement.newValue").value(longValue.substring(0, 200)))
                    .andExpect(jsonPath("$[0].changes.statement.truncated", is(true)))
                    .andExpect(jsonPath("$[0].truncated", is(true)));
        }

        @Test
        void timelineExpandTrueReturnsFullValueNotTruncated() throws Exception {
            var reqId = UUID.randomUUID();
            var longValue = "B".repeat(300);
            var changes = Map.of("statement", new FieldChange("short", longValue));
            var entry = new TimelineEntry(
                    2,
                    "MOD",
                    Instant.parse("2026-03-21T05:00:00Z"),
                    "test-user",
                    null,
                    ChangeCategory.REQUIREMENT,
                    reqId,
                    Map.of("statement", longValue),
                    changes);
            when(auditService.getRequirementTimeline(eq(reqId), any(), any(), any(), any(), eq(100), eq(0)))
                    .thenReturn(List.of(entry));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/timeline").param("expand", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].changes.statement.newValue").value(longValue))
                    .andExpect(jsonPath("$[0].changes.statement.truncated", is(false)))
                    .andExpect(jsonPath("$[0].truncated", is(false)));
        }

        @Test
        void passesFilterParams() throws Exception {
            var reqId = UUID.randomUUID();
            when(auditService.getRequirementTimeline(
                            eq(reqId),
                            eq(ChangeCategory.RELATION),
                            any(),
                            eq(Instant.parse("2026-01-01T00:00:00Z")),
                            any(),
                            eq(100),
                            eq(0)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/timeline")
                            .param("changeCategory", "RELATION")
                            .param("from", "2026-01-01T00:00:00Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", is(0)));
        }

        @Test
        void invalidChangeCategory_returns400() throws Exception {
            var reqId = UUID.randomUUID();

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/timeline").param("changeCategory", "INVALID"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void notFound_returns404() throws Exception {
            var reqId = UUID.randomUUID();
            when(auditService.getRequirementTimeline(eq(reqId), any(), any(), any(), any(), eq(100), eq(0)))
                    .thenThrow(new NotFoundException("Not found"));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/timeline"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")));
        }
    }

    @Nested
    class VersionDiff {

        @Test
        void returns200WithStructuredDiff() throws Exception {
            var reqId = UUID.randomUUID();
            var relId = UUID.randomUUID();
            var linkId = UUID.randomUUID();

            var fieldChanges = Map.of("title", new FieldChange("Old Title", "New Title"));
            var relationChanges = List.of(
                    new RelationChange(relId, ChangeType.ADDED, Map.of("relationType", "DEPENDS_ON"), Map.of()));
            var linkChanges = List.of(new TraceabilityLinkChange(
                    linkId, ChangeType.REMOVED, Map.of("artifactType", "CODE_FILE"), Map.of()));

            var diff = new RequirementVersionDiff(reqId, 1, 5, fieldChanges, relationChanges, linkChanges);
            when(auditService.getRequirementDiff(reqId, 1, 5)).thenReturn(diff);

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/diff")
                            .param("fromRevision", "1")
                            .param("toRevision", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requirementId", is(reqId.toString())))
                    .andExpect(jsonPath("$.fromRevision", is(1)))
                    .andExpect(jsonPath("$.toRevision", is(5)))
                    .andExpect(jsonPath("$.fieldChanges.title.oldValue", is("Old Title")))
                    .andExpect(jsonPath("$.fieldChanges.title.newValue", is("New Title")))
                    .andExpect(jsonPath("$.relationChanges[0].changeType", is("ADDED")))
                    .andExpect(jsonPath("$.relationChanges[0].relationId", is(relId.toString())))
                    .andExpect(jsonPath("$.traceabilityLinkChanges[0].changeType", is("REMOVED")))
                    .andExpect(jsonPath("$.traceabilityLinkChanges[0].linkId", is(linkId.toString())));
        }

        @Test
        void notFound_returns404() throws Exception {
            var reqId = UUID.randomUUID();
            when(auditService.getRequirementDiff(reqId, 1, 5))
                    .thenThrow(new NotFoundException("Requirement not found"));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/diff")
                            .param("fromRevision", "1")
                            .param("toRevision", "5"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")));
        }

        @Test
        void invalidRevisions_returns422() throws Exception {
            var reqId = UUID.randomUUID();
            when(auditService.getRequirementDiff(reqId, 5, 1))
                    .thenThrow(new DomainValidationException("fromRevision must be less than toRevision"));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/diff")
                            .param("fromRevision", "5")
                            .param("toRevision", "1"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("validation_error")));
        }

        @Test
        void missingParams_returns400() throws Exception {
            var reqId = UUID.randomUUID();

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/diff")).andExpect(status().isBadRequest());
        }

        @Test
        void emptyDiff_returnsEmptyCollections() throws Exception {
            var reqId = UUID.randomUUID();
            var diff = new RequirementVersionDiff(reqId, 1, 2, Map.of(), List.of(), List.of());
            when(auditService.getRequirementDiff(reqId, 1, 2)).thenReturn(diff);

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/diff")
                            .param("fromRevision", "1")
                            .param("toRevision", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldChanges").isEmpty())
                    .andExpect(jsonPath("$.relationChanges").isEmpty())
                    .andExpect(jsonPath("$.traceabilityLinkChanges").isEmpty());
        }
    }

    @Nested
    class TraceabilityLinkHistory {

        private static TraceabilityLink createLink(Requirement req) {
            var link = new TraceabilityLink(req, ArtifactType.CODE_FILE, "src/Example.java", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            setField(link, "createdAt", Instant.now());
            setField(link, "updatedAt", Instant.now());
            return link;
        }

        @Test
        void returns200WithRevisions() throws Exception {
            var req = createRequirement("REQ-001");
            var link = createLink(req);
            var revision = new TraceabilityLinkRevision(1, Instant.now(), "ADD", "test-user", null, link);
            when(auditService.getTraceabilityLinkHistory(req.getId(), link.getId()))
                    .thenReturn(List.of(revision));

            mockMvc.perform(get("/api/v1/requirements/" + req.getId() + "/traceability/" + link.getId() + "/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", is(1)))
                    .andExpect(jsonPath("$[0].revisionType", is("ADD")))
                    .andExpect(jsonPath("$[0].actor", is("test-user")))
                    .andExpect(jsonPath("$[0].snapshot.artifactType", is("CODE_FILE")))
                    .andExpect(jsonPath("$[0].snapshot.artifactIdentifier", is("src/Example.java")));
        }

        @Test
        void notFound_returns404() throws Exception {
            var reqId = UUID.randomUUID();
            var linkId = UUID.randomUUID();
            when(auditService.getTraceabilityLinkHistory(reqId, linkId)).thenThrow(new NotFoundException("Not found"));

            mockMvc.perform(get("/api/v1/requirements/" + reqId + "/traceability/" + linkId + "/history"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")));
        }

        @Test
        void mismatchedRequirement_returns404() throws Exception {
            var wrongReqId = UUID.randomUUID();
            var linkId = UUID.randomUUID();
            when(auditService.getTraceabilityLinkHistory(wrongReqId, linkId))
                    .thenThrow(new NotFoundException("Traceability link not found: " + linkId));

            mockMvc.perform(get("/api/v1/requirements/" + wrongReqId + "/traceability/" + linkId + "/history"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code", is("not_found")))
                    .andExpect(jsonPath("$.error.message", is("Traceability link not found: " + linkId)));
        }
    }
}
