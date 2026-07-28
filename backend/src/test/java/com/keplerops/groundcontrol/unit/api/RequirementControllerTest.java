package com.keplerops.groundcontrol.unit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.service.AuditService;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementFilter;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementWithLinks;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.service.UpdateRequirementCommand;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Split from RequirementControllerTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
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

        @Test
        void uidPrefixOnly_returns201AndServiceCalledWithPrefix() throws Exception {
            var req = createRequirement("PLAT-001");
            when(requirementService.create(any(CreateRequirementCommand.class))).thenReturn(req);

            mockMvc.perform(post("/api/v1/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "uidPrefix", "PLAT",
                                    "title", "Test Title",
                                    "statement", "Test Statement"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.uid", is("PLAT-001")));

            var captor = org.mockito.ArgumentCaptor.forClass(CreateRequirementCommand.class);
            verify(requirementService).create(captor.capture());
            var cmd = captor.getValue();
            assertThat(cmd.uidPrefix()).isEqualTo("PLAT");
            assertThat(cmd.uid()).isNull();
        }

        @Test
        void bothUidAndUidPrefix_returns422() throws Exception {
            mockMvc.perform(post("/api/v1/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "uid", "REQ-001",
                                    "uidPrefix", "PLAT",
                                    "title", "Title",
                                    "statement", "Stmt"))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("validation_error")));
        }

        @Test
        void neitherUidNorUidPrefix_returns422() throws Exception {
            mockMvc.perform(post("/api/v1/requirements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "Title",
                                    "statement", "Stmt"))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code", is("validation_error")));
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
}
