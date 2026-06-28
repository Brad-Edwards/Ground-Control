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

import com.keplerops.groundcontrol.api.architecturemodel.ArchitectureModelController;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElement;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelDiffEntry;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelDiffResult;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelDiffStatus;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementStateCommand;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelProvenanceSource;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelService;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelSnapshotView;
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
@WebMvcTest(ArchitectureModelController.class)
class ArchitectureModelControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111120");
    private static final UUID SNAPSHOT_ID = UUID.fromString("22222222-2222-2222-2222-222222222220");
    private static final UUID ELEMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333320");
    private static final UUID SNAPSHOT_B_ID = UUID.fromString("44444444-4444-4444-4444-444444444420");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArchitectureModelService architectureModelService;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void createSnapshotReturnsGraphNativeElements() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(architectureModelService.createSnapshot(any())).thenReturn(snapshotView());

        mockMvc.perform(
                        post("/api/v1/architecture-models/snapshots")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "modelVersion": "architecture-model/v1",
                                  "commitSha": "25c991231cf2a1464792846b083d1bd885299b3c",
                                  "source": "MANUAL",
                                  "elements": [
                                    {
                                      "stableKey": "component:api",
                                      "elementKind": "COMPONENT",
                                      "label": "API",
                                      "provenanceSource": "DECLARATION",
                                      "provenanceKey": "manual:api",
                                      "commitSha": "25c991231cf2a1464792846b083d1bd885299b3c"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(SNAPSHOT_ID.toString())))
                .andExpect(jsonPath("$.modelVersion", is("architecture-model/v1")))
                .andExpect(jsonPath("$.elements", hasSize(1)))
                .andExpect(jsonPath("$.elements[0].id", is(ELEMENT_ID.toString())))
                .andExpect(jsonPath("$.elements[0].graphNodeId", is("ARCHITECTURE_MODEL_ELEMENT:" + ELEMENT_ID)));
    }

    @Test
    void listSnapshotsReturnsSummariesWithoutElementPayloads() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(architectureModelService.listSnapshots(PROJECT_ID)).thenReturn(List.of(snapshot()));

        mockMvc.perform(get("/api/v1/architecture-models/snapshots").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(SNAPSHOT_ID.toString())))
                .andExpect(jsonPath("$[0].elementCount", is(1)))
                .andExpect(jsonPath("$[0].flowCount", is(0)))
                .andExpect(jsonPath("$[0].elements").doesNotExist());
    }

    @Test
    void diffSnapshotsReturnsStatusEntries() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(architectureModelService.diff(PROJECT_ID, SNAPSHOT_ID, SNAPSHOT_B_ID))
                .thenReturn(new ArchitectureModelDiffResult(
                        SNAPSHOT_ID,
                        SNAPSHOT_B_ID,
                        List.of(new ArchitectureModelDiffEntry(
                                "component:api", ArchitectureModelDiffStatus.CHANGED, "API changed"))));

        mockMvc.perform(get("/api/v1/architecture-models/diff")
                        .param("project", "ground-control")
                        .param("fromSnapshotId", SNAPSHOT_ID.toString())
                        .param("toSnapshotId", SNAPSHOT_B_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].stableKey", is("component:api")))
                .andExpect(jsonPath("$.entries[0].status", is("CHANGED")));
    }

    private static ArchitectureModelSnapshot snapshot() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var snapshot = new ArchitectureModelSnapshot(project, null, "architecture-model/v1", COMMIT, "MANUAL", "codex");
        setField(snapshot, "id", SNAPSHOT_ID);
        setField(snapshot, "createdAt", Instant.parse("2026-06-28T10:00:00Z"));
        setField(snapshot, "updatedAt", Instant.parse("2026-06-28T10:00:00Z"));
        snapshot.setCounts(1, 0);
        return snapshot;
    }

    private static ArchitectureModelSnapshotView snapshotView() {
        var snapshot = snapshot();
        var project = snapshot.getProject();
        var element = new ArchitectureModelElement(project, "component:api", ArchitectureModelElementKind.COMPONENT);
        setField(element, "id", ELEMENT_ID);
        var state = new ArchitectureModelElementState(
                project,
                snapshot,
                element,
                new ArchitectureModelElementStateCommand(
                        "component:api",
                        ArchitectureModelElementKind.COMPONENT,
                        "API",
                        "summary",
                        "backend/src/main/java/App.java",
                        "backend",
                        "internal",
                        null,
                        null,
                        ArchitectureFlowDirection.UNIDIRECTIONAL,
                        ArchitectureModelProvenanceSource.DECLARATION,
                        "manual:api",
                        "manual",
                        "manual",
                        "1.0.0",
                        "manual",
                        "1",
                        null,
                        COMMIT,
                        Map.of()));
        return new ArchitectureModelSnapshotView(snapshot, List.of(state));
    }
}
