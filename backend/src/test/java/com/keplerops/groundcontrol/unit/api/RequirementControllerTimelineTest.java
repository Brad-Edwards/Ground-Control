package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.api.requirements.RequirementController;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.service.AuditService;
import com.keplerops.groundcontrol.domain.requirements.service.ChangeType;
import com.keplerops.groundcontrol.domain.requirements.service.FieldChange;
import com.keplerops.groundcontrol.domain.requirements.service.RelationChange;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementVersionDiff;
import com.keplerops.groundcontrol.domain.requirements.service.TimelineEntry;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityLinkChange;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityLinkRevision;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.ChangeCategory;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Split from RequirementControllerTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RequirementController.class)
class RequirementControllerTimelineTest {
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

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
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
