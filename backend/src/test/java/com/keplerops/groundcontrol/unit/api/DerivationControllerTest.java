package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.derivation.DerivationController;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelAssignment;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelBoundary;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelGap;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelSnapshot;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationCaptureLimit;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.service.BoundaryModelBuildResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationCaptureLimitDraft;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationRunResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationService;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(DerivationController.class)
class DerivationControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000001114");
    private static final UUID FACT_ID = UUID.fromString("00000000-0000-0000-0000-000000002114");
    private static final UUID LIMIT_ID = UUID.fromString("00000000-0000-0000-0000-000000003114");
    private static final UUID BOUNDARY_MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000004114");
    private static final UUID BOUNDARY_ID = UUID.fromString("00000000-0000-0000-0000-000000005114");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000006114");
    private static final UUID GAP_ID = UUID.fromString("00000000-0000-0000-0000-000000007114");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";
    private static final Instant NOW = Instant.parse("2026-06-13T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DerivationService derivationService;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void runReturnsPersistedFactsWithProvenanceAndCaptureLimits() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(derivationService.run(any())).thenReturn(makeResult());

        mockMvc.perform(
                        post("/api/v1/derivations/runs")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "scopeMode": "PATH_SET",
                                  "commitSha": "25c991231cf2a1464792846b083d1bd885299b3c",
                                  "paths": ["backend/src/main/java/App.java"],
                                  "languages": ["java", "terraform"],
                                  "surfaces": ["application", "iac"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$.run.factCount", is(1)))
                .andExpect(jsonPath("$.facts", hasSize(1)))
                .andExpect(jsonPath("$.facts[0].factKind", is("COMPONENT")))
                .andExpect(jsonPath("$.facts[0].provenance.toolName", is("stub-deriver")))
                .andExpect(jsonPath("$.facts[0].provenance.commitSha", is(COMMIT)))
                .andExpect(jsonPath("$.captureLimits", hasSize(1)))
                .andExpect(jsonPath("$.captureLimits[0].reason", is("UNSUPPORTED_SURFACE")));
    }

    @Test
    void listRunsReturnsProjectScopedRuns() throws Exception {
        var result = makeResult();
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(derivationService.listRuns(PROJECT_ID)).thenReturn(List.of(result.run()));

        mockMvc.perform(get("/api/v1/derivations/runs").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$[0].scopeMode", is("PATH_SET")))
                .andExpect(jsonPath("$[0].factCount", is(1)));
    }

    @Test
    void getRunReturnsRun() throws Exception {
        var result = makeResult();
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(derivationService.getRun(PROJECT_ID, RUN_ID)).thenReturn(result.run());

        mockMvc.perform(get("/api/v1/derivations/runs/{id}", RUN_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$.projectIdentifier", is("ground-control")))
                .andExpect(jsonPath("$.scopeMode", is("PATH_SET")));
    }

    @Test
    void getBoundaryModelReturnsCanonicalSnapshot() throws Exception {
        var result = makeResult();
        var boundaryModel = makeBoundaryModel(result.run());
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(derivationService.getBoundaryModel(PROJECT_ID, RUN_ID)).thenReturn(boundaryModel);

        mockMvc.perform(get("/api/v1/derivations/runs/{id}/boundary-model", RUN_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(BOUNDARY_MODEL_ID.toString())))
                .andExpect(jsonPath("$.boundarySetVersion", is("boundary-set/abc123")))
                .andExpect(jsonPath("$.architectureModelVersion", is("architecture-model/25c991231cf2/abc123")))
                .andExpect(jsonPath("$.boundaryCount", is(1)))
                .andExpect(jsonPath("$.assignmentCount", is(1)))
                .andExpect(jsonPath("$.gapCount", is(1)))
                .andExpect(jsonPath("$.boundaries[0].boundaryKey", is("backend-api")))
                .andExpect(jsonPath("$.boundaries[0].source", is("MERGED")))
                .andExpect(jsonPath("$.assignments[0].boundaryKey", is("backend-api")))
                .andExpect(jsonPath("$.assignments[0].sourceFactKey", is("component:backend")))
                .andExpect(jsonPath("$.gaps[0].reason", is("UNASSIGNED_BOUNDARY")));
    }

    @Test
    void listFactsFiltersByRunAndKind() throws Exception {
        var result = makeResult();
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(derivationService.listFacts(PROJECT_ID, RUN_ID, SystemModelFactKind.COMPONENT))
                .thenReturn(result.facts());

        mockMvc.perform(get("/api/v1/derivations/facts")
                        .param("project", "ground-control")
                        .param("runId", RUN_ID.toString())
                        .param("factKind", "COMPONENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].derivationRunId", is(RUN_ID.toString())));
    }

    @Test
    void listCaptureLimitsReturnsQueryableReasonCodes() throws Exception {
        var result = makeResult();
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(derivationService.listCaptureLimits(PROJECT_ID, RUN_ID, CaptureLimitReason.UNSUPPORTED_SURFACE))
                .thenReturn(result.captureLimits());

        mockMvc.perform(get("/api/v1/derivations/capture-limits")
                        .param("project", "ground-control")
                        .param("runId", RUN_ID.toString())
                        .param("reason", "UNSUPPORTED_SURFACE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].language", is("java")))
                .andExpect(jsonPath("$[0].surface", is("iac")));
    }

    private DerivationRunResult makeResult() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var run = new DerivationRun(
                project,
                DerivationScopeMode.PATH_SET,
                COMMIT,
                null,
                List.of("backend/src/main/java/App.java"),
                List.of("java", "terraform"),
                List.of("application", "iac"),
                "alice",
                NOW,
                1);
        setField(run, "id", RUN_ID);
        setField(run, "createdAt", NOW);
        setField(run, "updatedAt", NOW);
        run.setResultCounts(1, 1);

        var fact = new SystemModelFact(
                project,
                run,
                new DerivedSystemModelFact(
                        SystemModelFactKind.COMPONENT,
                        "component:backend",
                        "Backend",
                        "Java backend component",
                        "backend/src/main/java/App.java",
                        Map.of("framework", "spring-web"),
                        new DerivationFactProvenance(
                                "stub-static-derivation",
                                "stub-deriver",
                                "0.1.0",
                                "stub-system-model",
                                "2026.06",
                                COMMIT,
                                NOW)));
        setField(fact, "id", FACT_ID);
        setField(fact, "createdAt", NOW);
        setField(fact, "updatedAt", NOW);

        var limit = new DerivationCaptureLimit(
                project,
                run,
                new DerivationCaptureLimitDraft(
                        null, CaptureLimitReason.UNSUPPORTED_SURFACE, "java", "iac", "No adapter", COMMIT, NOW));
        setField(limit, "id", LIMIT_ID);
        setField(limit, "createdAt", NOW);
        setField(limit, "updatedAt", NOW);
        return new DerivationRunResult(run, List.of(fact), List.of(limit));
    }

    private BoundaryModelBuildResult makeBoundaryModel(DerivationRun run) {
        var snapshot = new BoundaryModelSnapshot(
                run.getProject(),
                run,
                "boundary-set/abc123",
                "architecture-model/25c991231cf2/abc123",
                "declared-digest");
        setField(snapshot, "id", BOUNDARY_MODEL_ID);
        setField(snapshot, "createdAt", NOW);
        setField(snapshot, "updatedAt", NOW);
        snapshot.setCounts(1, 1, 1);

        var boundary = new BoundaryModelBoundary(
                run.getProject(),
                snapshot,
                "backend-api",
                "Backend API",
                "Controllers and DTOs",
                "MERGED",
                List.of("backend/src/main/java/com/keplerops/groundcontrol/api/**"),
                List.of("application"),
                List.of("boundary:backend-api"));
        setField(boundary, "id", BOUNDARY_ID);
        setField(boundary, "createdAt", NOW);
        setField(boundary, "updatedAt", NOW);

        var assignment = new BoundaryModelAssignment(
                run.getProject(),
                snapshot,
                boundary,
                "component:backend",
                "COMPONENT",
                "backend/src/main/java/com/keplerops/groundcontrol/api/AppController.java",
                "PATH_SELECTOR");
        setField(assignment, "id", ASSIGNMENT_ID);
        setField(assignment, "createdAt", NOW);
        setField(assignment, "updatedAt", NOW);

        var gap = new BoundaryModelGap(
                run.getProject(),
                snapshot,
                "flow:frontend",
                "DATA_FLOW",
                "frontend/src/App.tsx",
                "UNASSIGNED_BOUNDARY",
                "No canonical boundary selector matched source path");
        setField(gap, "id", GAP_ID);
        setField(gap, "createdAt", NOW);
        setField(gap, "updatedAt", NOW);

        return new BoundaryModelBuildResult(snapshot, List.of(boundary), List.of(assignment), List.of(gap));
    }
}
