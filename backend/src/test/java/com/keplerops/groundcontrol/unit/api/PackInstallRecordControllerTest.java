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

import com.keplerops.groundcontrol.api.packregistry.PackInstallRecordController;
import com.keplerops.groundcontrol.api.packregistry.PackRegistryAccessGuard;
import com.keplerops.groundcontrol.domain.packregistry.model.PackInstallRecord;
import com.keplerops.groundcontrol.domain.packregistry.service.InstallPackCommand;
import com.keplerops.groundcontrol.domain.packregistry.service.PackInstallOrchestrator;
import com.keplerops.groundcontrol.domain.packregistry.state.InstallOutcome;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.packregistry.state.TrustOutcome;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(PackInstallRecordController.class)
class PackInstallRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackInstallOrchestrator orchestrator;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private PackRegistryAccessGuard accessGuard;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RECORD_ID = UUID.fromString("00000000-0000-0000-0000-000000000080");
    private static final String ADMIN_ACTOR = "pack-admin";

    private Project makeProject() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    private PackInstallRecord makeRecord() {
        var project = makeProject();
        var record = new PackInstallRecord(
                project, "nist-800-53", PackType.CUSTOM, TrustOutcome.TRUSTED, InstallOutcome.INSTALLED);
        setField(record, "id", RECORD_ID);
        setField(record, "createdAt", Instant.now());
        setField(record, "updatedAt", Instant.now());
        record.setResolvedVersion("1.0.0");
        record.setPerformedBy(ADMIN_ACTOR);
        return record;
    }

    @BeforeEach
    void setUp() {
        when(accessGuard.requireAdminActor()).thenReturn(ADMIN_ACTOR);
    }

    @Test
    void installReturnsCreated() throws Exception {
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        when(orchestrator.installPack(any(InstallPackCommand.class))).thenReturn(makeRecord());

        mockMvc.perform(
                        post("/api/v1/pack-install-records/install")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                    "packId":"nist-800-53"
                }
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packId", is("nist-800-53")))
                .andExpect(jsonPath("$.installOutcome", is("INSTALLED")));
    }

    @Test
    void upgradeReturnsOk() throws Exception {
        var record = makeRecord();
        setField(record, "installOutcome", InstallOutcome.UPGRADED);
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        when(orchestrator.upgradePack(any(InstallPackCommand.class))).thenReturn(record);

        mockMvc.perform(
                        post("/api/v1/pack-install-records/upgrade")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                    "packId":"nist-800-53",
                    "versionConstraint":"^1.0.0"
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installOutcome", is("UPGRADED")));
    }

    @Test
    void installRejectedReturnsUnprocessableEntity() throws Exception {
        var record = makeRecord();
        setField(record, "installOutcome", InstallOutcome.REJECTED);
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        when(orchestrator.installPack(any(InstallPackCommand.class))).thenReturn(record);

        mockMvc.perform(
                        post("/api/v1/pack-install-records/install")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                    "packId":"nist-800-53"
                }
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.installOutcome", is("REJECTED")));
    }

    @Test
    void upgradeFailureReturnsUnprocessableEntity() throws Exception {
        var record = makeRecord();
        setField(record, "installOutcome", InstallOutcome.FAILED);
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        when(orchestrator.upgradePack(any(InstallPackCommand.class))).thenReturn(record);

        mockMvc.perform(
                        post("/api/v1/pack-install-records/upgrade")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {
                    "packId":"nist-800-53",
                    "versionConstraint":"^1.0.0"
                }
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.installOutcome", is("FAILED")));
    }

    @Test
    void listReturnsRecords() throws Exception {
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        when(orchestrator.listInstallRecords(PROJECT_ID)).thenReturn(List.of(makeRecord()));

        mockMvc.perform(get("/api/v1/pack-install-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].packId", is("nist-800-53")));
    }

    @Test
    void getReturnsRecord() throws Exception {
        when(orchestrator.getInstallRecord(RECORD_ID)).thenReturn(makeRecord());

        mockMvc.perform(get("/api/v1/pack-install-records/" + RECORD_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId", is("nist-800-53")));
    }
}
