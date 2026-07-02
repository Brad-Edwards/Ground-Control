package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.packregistry.PackRegistryAccessGuard;
import com.keplerops.groundcontrol.api.packregistry.PackRegistryController;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredControlPackEntry;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportService;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryService;
import com.keplerops.groundcontrol.domain.packregistry.service.PackResolver;
import com.keplerops.groundcontrol.domain.packregistry.service.RegisterPackCommand;
import com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack;
import com.keplerops.groundcontrol.domain.packregistry.service.ThreatRulePackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.UpdatePackRegistryEntryCommand;
import com.keplerops.groundcontrol.domain.packregistry.state.CatalogStatus;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(PackRegistryController.class)
class PackRegistryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackRegistryService registryService;

    @MockitoBean
    private PackRegistryImportService importService;

    @MockitoBean
    private PackResolver packResolver;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private PackRegistryAccessGuard accessGuard;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(accessGuard.requireAdminActor()).thenReturn("pack-admin");
    }

    private Project makeProject() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    private PackRegistryEntry makeEntry() {
        var project = makeProject();
        var entry = new PackRegistryEntry(project, "nist-800-53", PackType.CONTROL_PACK, "1.0.0");
        setField(entry, "id", ENTRY_ID);
        setField(entry, "createdAt", Instant.now());
        setField(entry, "updatedAt", Instant.now());
        entry.setPublisher("NIST");
        entry.setDescription("NIST SP 800-53 controls");
        entry.setControlPackEntries(List.of(new RegisteredControlPackEntry(
                "AC-1",
                "Access Control Policy",
                com.keplerops.groundcontrol.domain.controls.state.ControlFunction.PREVENTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)));
        return entry;
    }

    @Test
    void registerReturnsCreated() throws Exception {
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        when(registryService.registerEntry(any(RegisterPackCommand.class))).thenReturn(makeEntry());

        mockMvc.perform(
                        post("/api/v1/pack-registry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"packId":"nist-800-53","packType":"CONTROL_PACK","version":"1.0.0","publisher":"NIST"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packId", is("nist-800-53")))
                .andExpect(jsonPath("$.packType", is("CONTROL_PACK")))
                .andExpect(jsonPath("$.version", is("1.0.0")))
                .andExpect(jsonPath("$.controlPackEntries[0].uid", is("AC-1")));
    }

    @Test
    void registerWithDependenciesConvertsCorrectly() throws Exception {
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        var captor = ArgumentCaptor.forClass(RegisterPackCommand.class);
        when(registryService.registerEntry(captor.capture())).thenReturn(makeEntry());

        mockMvc.perform(
                        post("/api/v1/pack-registry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"packId":"nist-800-53","packType":"CONTROL_PACK","version":"1.0.0",
                 "dependencies":[{"packId":"dep-pack","versionConstraint":"^1.0.0"}]}
                """))
                .andExpect(status().isCreated());

        var dependencies = captor.getValue().dependencies();
        assertEquals(1, dependencies.size());
        assertEquals("dep-pack", dependencies.get(0).packId());
        assertEquals("^1.0.0", dependencies.get(0).versionConstraint());
    }

    @Test
    void registerThreatRulePackMapsThreatRuleEntries() throws Exception {
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        var captor = ArgumentCaptor.forClass(RegisterPackCommand.class);
        when(registryService.registerEntry(captor.capture())).thenReturn(makeEntry());

        mockMvc.perform(
                        post("/api/v1/pack-registry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"packId":"stride-baseline","packType":"THREAT_RULE_PACK","version":"1.0.0",
                 "threatRuleEntries":[{"ruleId":"stride.process.spoofing","title":"Process spoofing",
                   "category":"STRIDE_BASELINE","strideCategory":"SPOOFING",
                   "targetElementKinds":["PROCESS"],"predicate":"ALWAYS"}]}
                """))
                .andExpect(status().isCreated());

        var content = captor.getValue().registrationContent();
        var threatContent = assertInstanceOf(ThreatRulePackRegistrationContent.class, content);
        assertEquals(1, threatContent.entries().size());
        assertEquals("stride.process.spoofing", threatContent.entries().get(0).ruleId());
    }

    @Test
    void importReturnsCreated() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(importService.importEntry(eq(PROJECT_ID), eq("catalog.json"), any(), any()))
                .thenReturn(makeEntry());

        var file = new MockMultipartFile(
                "file", "catalog.json", "application/json", "{\"catalog\":{}}".getBytes(StandardCharsets.UTF_8));
        var options = new MockMultipartFile(
                "options",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                {"format":"OSCAL_JSON","packId":"nist-sp800-53-rev5","version":"5.1.0"}
                """
                        .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/pack-registry/import")
                        .file(file)
                        .file(options)
                        .param("project", "ground-control"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packId", is("nist-800-53")))
                .andExpect(jsonPath("$.controlPackEntries[0].uid", is("AC-1")));
    }

    @Test
    void listReturnsEntries() throws Exception {
        when(projectService.resolveProjectId(null)).thenReturn(PROJECT_ID);
        when(registryService.listEntries(PROJECT_ID)).thenReturn(List.of(makeEntry()));

        mockMvc.perform(get("/api/v1/pack-registry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].packId", is("nist-800-53")));
    }

    @Test
    void listVersionsReturnsVersions() throws Exception {
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);
        when(registryService.listVersions(PROJECT_ID, "nist-800-53")).thenReturn(List.of(makeEntry()));

        mockMvc.perform(get("/api/v1/pack-registry/nist-800-53"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getEntryReturnsSpecificVersion() throws Exception {
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);
        when(registryService.findEntry(PROJECT_ID, "nist-800-53", "1.0.0")).thenReturn(makeEntry());

        mockMvc.perform(get("/api/v1/pack-registry/nist-800-53/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId", is("nist-800-53")))
                .andExpect(jsonPath("$.version", is("1.0.0")));
    }

    @Test
    void withdrawReturnsUpdatedEntry() throws Exception {
        var entry = makeEntry();
        setField(entry, "catalogStatus", CatalogStatus.WITHDRAWN);
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);
        when(registryService.withdrawEntry(PROJECT_ID, "nist-800-53", "1.0.0")).thenReturn(entry);

        mockMvc.perform(put("/api/v1/pack-registry/nist-800-53/1.0.0/withdraw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogStatus", is("WITHDRAWN")));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/pack-registry/nist-800-53/1.0.0")).andExpect(status().isNoContent());

        verify(registryService).deleteEntry(PROJECT_ID, "nist-800-53", "1.0.0");
    }

    @Test
    void resolveReturnsResolvedPack() throws Exception {
        var entry = makeEntry();
        var resolved = new ResolvedPack(entry, "1.0.0", "https://registry.example.com", "sha256:abc", List.of());
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);
        when(packResolver.resolve(eq(PROJECT_ID), eq("nist-800-53"), any())).thenReturn(resolved);
        when(packResolver.checkCompatibility(resolved)).thenReturn(true);

        mockMvc.perform(post("/api/v1/pack-registry/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"packId":"nist-800-53"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedVersion", is("1.0.0")))
                .andExpect(jsonPath("$.compatible", is(true)));
    }

    @Test
    void resolvePropagatesDependencyCompatibility() throws Exception {
        var entry = makeEntry();
        var dependencyEntry = new PackRegistryEntry(makeProject(), "dep-pack", PackType.CONTROL_PACK, "1.0.0");
        var dependency =
                new ResolvedPack(dependencyEntry, "1.0.0", "https://registry.example.com/dep", "sha256:def", List.of());
        var resolved =
                new ResolvedPack(entry, "1.0.0", "https://registry.example.com", "sha256:abc", List.of(dependency));
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);
        when(packResolver.resolve(eq(PROJECT_ID), eq("nist-800-53"), any())).thenReturn(resolved);
        when(packResolver.checkCompatibility(resolved)).thenReturn(false);
        when(packResolver.checkCompatibility(dependency)).thenReturn(false);

        mockMvc.perform(post("/api/v1/pack-registry/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"packId":"nist-800-53"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible", is(false)))
                .andExpect(jsonPath("$.resolvedDependencies[0].compatible", is(false)));
    }

    @Test
    void checkCompatibilityReturnsResult() throws Exception {
        var entry = makeEntry();
        var resolved = new ResolvedPack(entry, "1.0.0", "https://registry.example.com", "sha256:abc", List.of());
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);
        when(packResolver.resolve(eq(PROJECT_ID), eq("nist-800-53"), any())).thenReturn(resolved);
        when(packResolver.checkCompatibility(resolved)).thenReturn(true);

        mockMvc.perform(post("/api/v1/pack-registry/check-compatibility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"packId":"nist-800-53"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId", is("nist-800-53")))
                .andExpect(jsonPath("$.compatible", is(true)));
    }

    @Test
    void updateReturnsUpdatedEntry() throws Exception {
        when(projectService.requireProjectId(null)).thenReturn(PROJECT_ID);
        var entry = makeEntry();
        entry.setPublisher("Updated Publisher");
        when(registryService.updateEntry(
                        eq(PROJECT_ID), eq("nist-800-53"), eq("1.0.0"), any(UpdatePackRegistryEntryCommand.class)))
                .thenReturn(entry);

        mockMvc.perform(
                        put("/api/v1/pack-registry/nist-800-53/1.0.0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"publisher":"Updated Publisher","controlPackEntries":[{"uid":"AC-1","title":"Access Control Policy","controlFunction":"PREVENTIVE"}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publisher", is("Updated Publisher")));
    }
}
