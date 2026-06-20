package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.evidencestate.EvidenceStateWorkspaceController;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceService;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(EvidenceStateWorkspaceController.class)
class EvidenceStateWorkspaceControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final Instant AS_OF = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvidenceStateWorkspaceService workspaceService;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void workspaceMapsQueryParamsAndReturnsComposedExplorer() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(workspaceService.workspace(PROJECT_ID, AS_OF, 30, true, ASSET_ID, CONTROL_ID))
                .thenReturn(sampleWorkspace());

        mockMvc.perform(get("/api/v1/evidence-state/workspace")
                        .param("project", "ground-control")
                        .param("asOf", AS_OF.toString())
                        .param("freshnessWindowDays", "30")
                        .param("includeSuperseded", "true")
                        .param("assetId", ASSET_ID.toString())
                        .param("controlId", CONTROL_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactCount", is(1)))
                .andExpect(jsonPath("$.observationCount", is(1)))
                .andExpect(jsonPath("$.evidenceArtifacts[0].uid", is("EV-001")))
                .andExpect(jsonPath("$.evidenceArtifacts[0].freshnessState", is("FRESH")))
                .andExpect(jsonPath("$.evidenceArtifacts[0].affectedAssets[0].targetIdentifier", is("ASSET-001")))
                .andExpect(jsonPath("$.observations[0].observationKey", is("patch_level")))
                .andExpect(jsonPath("$.observations[0].linkedFindings[0].targetIdentifier", is("FIND-001")));

        verify(workspaceService).workspace(PROJECT_ID, AS_OF, 30, true, ASSET_ID, CONTROL_ID);
    }

    @Test
    void workspaceRejectsNonPositiveFreshnessWindow() throws Exception {
        mockMvc.perform(get("/api/v1/evidence-state/workspace")
                        .param("project", "ground-control")
                        .param("freshnessWindowDays", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void workspaceUsesDefaultFreshnessWindowAndSupersededFlag() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(workspaceService.workspace(PROJECT_ID, null, 90, false, null, null))
                .thenReturn(emptyWorkspace());

        mockMvc.perform(get("/api/v1/evidence-state/workspace").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactCount", is(0)))
                .andExpect(jsonPath("$.observationCount", is(0)));

        verify(workspaceService).workspace(PROJECT_ID, null, 90, false, null, null);
    }

    private static EvidenceStateWorkspaceResult sampleWorkspace() {
        var asset = new EvidenceStateWorkspaceResult.WorkspaceAsset(
                ASSET_ID, "ASSET-001", "Payments API", "SERVICE", false);
        var assetLink = new EvidenceStateWorkspaceResult.WorkspaceLink(ASSET_ID, "ASSET-001", "Payments API", null);
        var findingLink = new EvidenceStateWorkspaceResult.WorkspaceLink(
                UUID.fromString("00000000-0000-0000-0000-000000000301"), "FIND-001", "Stale patch", null);
        var source = new EvidenceStateWorkspaceResult.ProvenanceSource(
                "OBSERVATION",
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                null,
                "source",
                "ASSET-001 patch_level");
        var artifact = new EvidenceStateWorkspaceResult.EvidenceArtifactItem(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                "EV-001",
                "Patch assurance",
                "Patch state is current",
                "OBSERVATION_SUMMARY",
                AS_OF,
                0,
                "FRESH",
                null,
                null,
                "L2",
                "HIGH",
                List.of(source),
                List.of(assetLink),
                List.of(),
                List.of(),
                List.of());
        var observation = new EvidenceStateWorkspaceResult.ObservationItem(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                ASSET_ID,
                "ASSET-001",
                "CONFIGURATION",
                "patch_level",
                "2026-05 cumulative update",
                "agent",
                "collector://patch",
                AS_OF,
                null,
                0,
                "FRESH",
                "HIGH",
                List.of(new EvidenceStateWorkspaceResult.WorkspaceLink(
                        artifact.id(), artifact.uid(), artifact.title(), null)),
                List.of(),
                List.of(findingLink));
        var counts = new EvidenceStateWorkspaceResult.EvidenceFreshnessCounts(2, 0, 0, 0, 2);
        return new EvidenceStateWorkspaceResult(
                List.of(asset), List.of(artifact), List.of(observation), counts, List.of());
    }

    private static EvidenceStateWorkspaceResult emptyWorkspace() {
        return new EvidenceStateWorkspaceResult(
                List.of(),
                List.of(),
                List.of(),
                new EvidenceStateWorkspaceResult.EvidenceFreshnessCounts(0, 0, 0, 0, 0),
                List.of());
    }
}
