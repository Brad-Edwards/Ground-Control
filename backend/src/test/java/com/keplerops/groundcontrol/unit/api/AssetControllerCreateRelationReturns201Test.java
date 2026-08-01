package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.assets.AssetController;
import com.keplerops.groundcontrol.domain.assets.model.AssetExternalId;
import com.keplerops.groundcontrol.domain.assets.model.AssetLink;
import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.service.AssetCycleEdge;
import com.keplerops.groundcontrol.domain.assets.service.AssetCycleResult;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.AssetSubgraphResult;
import com.keplerops.groundcontrol.domain.assets.service.AssetTopologyService;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkType;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Split from AssetControllerTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(AssetController.class)
class AssetControllerCreateRelationReturns201Test {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssetService assetService;

    @MockitoBean
    private AssetTopologyService topologyService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private OperationalAsset makeAsset() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var asset = new OperationalAsset(project, "ASSET-001", "Web Server");
        asset.setAssetType(AssetType.SERVICE);
        asset.setDescription("A web server");
        setField(asset, "id", ASSET_ID);
        setField(asset, "createdAt", Instant.now());
        setField(asset, "updatedAt", Instant.now());
        return asset;
    }

    // --- Asset Link tests ---

    private AssetLink makeLink() {
        var asset = makeAsset();
        var link = new AssetLink(asset, AssetLinkTargetType.REQUIREMENT, null, "GC-M010", AssetLinkType.IMPLEMENTS);
        setField(link, "id", UUID.randomUUID());
        setField(link, "createdAt", Instant.now());
        setField(link, "updatedAt", Instant.now());
        return link;
    }

    // --- External ID tests ---

    private AssetExternalId makeExternalId() {
        var asset = makeAsset();
        var extId = new AssetExternalId(asset, "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc");
        extId.setConfidence("HIGH");
        extId.setCollectedAt(Instant.parse("2026-03-30T12:00:00Z"));
        setField(extId, "id", UUID.randomUUID());
        setField(extId, "createdAt", Instant.now());
        setField(extId, "updatedAt", Instant.now());
        return extId;
    }

    @Test
    void createRelationReturns201() throws Exception {
        var source = makeAsset();
        var target = makeAsset();
        setField(target, "id", UUID.randomUUID());
        target.setName("Database");

        var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
        setField(relation, "id", UUID.randomUUID());
        setField(relation, "createdAt", Instant.now());
        setField(relation, "updatedAt", Instant.now());
        relation.setDescription("Observed dependency");

        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.createRelation(
                        eq(PROJECT_ID),
                        any(com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand.class),
                        eq(ASSET_ID)))
                .thenReturn(relation);

        mockMvc.perform(post("/api/v1/assets/{id}/relations", ASSET_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                {"targetId":"%s","relationType":"DEPENDS_ON","description":"Observed dependency"}
                """
                                        .formatted(target.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationType", is("DEPENDS_ON")))
                .andExpect(jsonPath("$.description", is("Observed dependency")))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void getRelationsReturnsList() throws Exception {
        var source = makeAsset();
        var target = makeAsset();
        setField(target, "id", UUID.randomUUID());

        var relation = new AssetRelation(source, target, AssetRelationType.COMMUNICATES_WITH);
        setField(relation, "id", UUID.randomUUID());
        setField(relation, "createdAt", Instant.now());
        setField(relation, "updatedAt", Instant.now());
        relation.setDescription("Service communication");

        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.getRelations(PROJECT_ID, ASSET_ID)).thenReturn(List.of(relation));

        mockMvc.perform(get("/api/v1/assets/{id}/relations", ASSET_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].relationType", is("COMMUNICATES_WITH")))
                .andExpect(jsonPath("$[0].description", is("Service communication")))
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
    }

    @Test
    void updateRelationReturns200() throws Exception {
        var source = makeAsset();
        var target = makeAsset();
        setField(target, "id", UUID.randomUUID());

        var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
        setField(relation, "id", UUID.randomUUID());
        setField(relation, "createdAt", Instant.now());
        setField(relation, "updatedAt", Instant.now());
        relation.setDescription("Refined dependency");
        relation.setSourceSystem("CMDB");
        relation.setConfidence("0.95");

        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.updateRelation(
                        eq(PROJECT_ID),
                        eq(ASSET_ID),
                        eq(relation.getId()),
                        any(com.keplerops.groundcontrol.domain.assets.service.UpdateAssetRelationCommand.class)))
                .thenReturn(relation);

        mockMvc.perform(
                        put("/api/v1/assets/{id}/relations/{relationId}", ASSET_ID, relation.getId())
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"description":"Refined dependency","sourceSystem":"CMDB","confidence":"0.95"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("Refined dependency")))
                .andExpect(jsonPath("$.sourceSystem", is("CMDB")))
                .andExpect(jsonPath("$.confidence", is("0.95")))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void detectCyclesReturnsList() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        var cycle = new AssetCycleResult(
                List.of("A", "B", "A"),
                List.of(
                        new AssetCycleEdge("A", "B", AssetRelationType.DEPENDS_ON),
                        new AssetCycleEdge("B", "A", AssetRelationType.DEPENDS_ON)));
        when(topologyService.detectCycles(PROJECT_ID)).thenReturn(List.of(cycle));

        mockMvc.perform(get("/api/v1/assets/topology/cycles").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].memberUids", hasSize(3)));
    }

    @Test
    void impactAnalysisReturnsList() throws Exception {
        var asset = makeAsset();
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(topologyService.impactAnalysis(PROJECT_ID, ASSET_ID)).thenReturn(Set.of(asset));

        mockMvc.perform(get("/api/v1/assets/{id}/topology/impact", ASSET_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void extractSubgraphReturnsResult() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        var result = new AssetSubgraphResult(List.of(makeAsset()), List.of());
        when(topologyService.extractSubgraph(eq(PROJECT_ID), any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/assets/topology/subgraph")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"rootUids":["ASSET-001"]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets", hasSize(1)))
                .andExpect(jsonPath("$.relations", hasSize(0)));
    }

    @Test
    void createLinkReturns201() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.createLink(eq(PROJECT_ID), eq(ASSET_ID), any())).thenReturn(makeLink());

        mockMvc.perform(
                        post("/api/v1/assets/{id}/links", ASSET_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"targetType":"REQUIREMENT","targetIdentifier":"GC-M010","linkType":"IMPLEMENTS"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType", is("REQUIREMENT")))
                .andExpect(jsonPath("$.targetIdentifier", is("GC-M010")))
                .andExpect(jsonPath("$.linkType", is("IMPLEMENTS")));
    }

    @Test
    void getLinksReturnsList() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.getLinksForAsset(PROJECT_ID, ASSET_ID)).thenReturn(List.of(makeLink()));

        mockMvc.perform(get("/api/v1/assets/{id}/links", ASSET_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].targetType", is("REQUIREMENT")));
    }

    @Test
    void getLinksWithTargetTypeFilter() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.getLinksForAssetByTargetType(PROJECT_ID, ASSET_ID, AssetLinkTargetType.REQUIREMENT))
                .thenReturn(List.of(makeLink()));

        mockMvc.perform(get("/api/v1/assets/{id}/links", ASSET_ID)
                        .param("project", "ground-control")
                        .param("target_type", "REQUIREMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void deleteLinkReturns204() throws Exception {
        var linkId = UUID.randomUUID();
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/assets/{id}/links/{linkId}", ASSET_ID, linkId)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(assetService).deleteLink(PROJECT_ID, ASSET_ID, linkId);
    }

    @Test
    void getLinksByTargetReturnsList() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.getLinksByTarget(PROJECT_ID, AssetLinkTargetType.REQUIREMENT, null, "GC-M010"))
                .thenReturn(List.of(makeLink()));

        mockMvc.perform(get("/api/v1/assets/links/by-target")
                        .param("target_type", "REQUIREMENT")
                        .param("target_identifier", "GC-M010")
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].assetUid", is("ASSET-001")));
    }

    @Test
    void createExternalIdReturns201() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.createExternalId(eq(PROJECT_ID), eq(ASSET_ID), any())).thenReturn(makeExternalId());

        mockMvc.perform(
                        post("/api/v1/assets/{id}/external-ids", ASSET_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                {"sourceSystem":"AWS","sourceId":"arn:aws:ec2:us-east-1:123:instance/i-abc","confidence":"HIGH"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceSystem", is("AWS")))
                .andExpect(jsonPath("$.sourceId", is("arn:aws:ec2:us-east-1:123:instance/i-abc")))
                .andExpect(jsonPath("$.confidence", is("HIGH")));
    }

    @Test
    void getExternalIdsReturnsList() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.getExternalIds(PROJECT_ID, ASSET_ID)).thenReturn(List.of(makeExternalId()));

        mockMvc.perform(get("/api/v1/assets/{id}/external-ids", ASSET_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceSystem", is("AWS")));
    }

    @Test
    void getExternalIdsBySourceFilter() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.getExternalIdsBySource(PROJECT_ID, ASSET_ID, "AWS")).thenReturn(List.of(makeExternalId()));

        mockMvc.perform(get("/api/v1/assets/{id}/external-ids", ASSET_ID)
                        .param("project", "ground-control")
                        .param("source_system", "AWS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceSystem", is("AWS")));
    }

    @Test
    void updateExternalIdReturnsUpdated() throws Exception {
        var updated = makeExternalId();
        updated.setConfidence("MEDIUM");
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.updateExternalId(eq(PROJECT_ID), eq(ASSET_ID), any(), any()))
                .thenReturn(updated);

        var extIdId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/assets/{id}/external-ids/{extIdId}", ASSET_ID, extIdId)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"confidence":"MEDIUM"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidence", is("MEDIUM")));
    }

    @Test
    void deleteExternalIdReturns204() throws Exception {
        var extIdId = UUID.randomUUID();
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/assets/{id}/external-ids/{extIdId}", ASSET_ID, extIdId)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(assetService).deleteExternalId(PROJECT_ID, ASSET_ID, extIdId);
    }

    @Test
    void findByExternalIdReturnsList() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.findByExternalId(PROJECT_ID, "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc"))
                .thenReturn(List.of(makeExternalId()));

        mockMvc.perform(get("/api/v1/assets/external-ids/by-source")
                        .param("source_system", "AWS")
                        .param("source_id", "arn:aws:ec2:us-east-1:123:instance/i-abc")
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].assetUid", is("ASSET-001")));
    }

    @Test
    void createRelationWithProvenanceReturns201() throws Exception {
        var source = makeAsset();
        var target = makeAsset();
        setField(target, "id", UUID.randomUUID());
        target.setName("Database");

        var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
        setField(relation, "id", UUID.randomUUID());
        setField(relation, "createdAt", Instant.now());
        setField(relation, "updatedAt", Instant.now());
        relation.setDescription("Observed dependency");
        relation.setSourceSystem("AWS_CONFIG");
        relation.setConfidence("0.95");

        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.createRelation(
                        eq(PROJECT_ID),
                        any(com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand.class),
                        eq(ASSET_ID)))
                .thenReturn(relation);

        mockMvc.perform(post("/api/v1/assets/{id}/relations", ASSET_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                {"targetId":"%s","relationType":"DEPENDS_ON","description":"Observed dependency","sourceSystem":"AWS_CONFIG","confidence":"0.95"}
                """
                                        .formatted(target.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationType", is("DEPENDS_ON")))
                .andExpect(jsonPath("$.description", is("Observed dependency")))
                .andExpect(jsonPath("$.sourceSystem", is("AWS_CONFIG")))
                .andExpect(jsonPath("$.confidence", is("0.95")));
    }

    @Test
    void createRelationForwardsKnowledgeStateIntoCommand() throws Exception {
        // GC-M018: knowledgeState on a topology edge round-trips through
        // the controller into CreateAssetRelationCommand. ArgumentCaptor
        // anchors the assertion to the actual command shape — without it,
        // a controller drop would still pass because the mock return value
        // is independent of the request body.
        var source = makeAsset();
        var target = makeAsset();
        setField(target, "id", UUID.randomUUID());
        var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
        setField(relation, "id", UUID.randomUUID());
        setField(relation, "createdAt", Instant.now());
        setField(relation, "updatedAt", Instant.now());
        relation.setKnowledgeState(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);

        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assetService.createRelation(
                        eq(PROJECT_ID),
                        any(com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand.class),
                        eq(ASSET_ID)))
                .thenReturn(relation);

        mockMvc.perform(post("/api/v1/assets/{id}/relations", ASSET_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                {"targetId":"%s","relationType":"DEPENDS_ON","knowledgeState":"UNKNOWN"}
                """
                                        .formatted(target.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.knowledgeState", is("UNKNOWN")));

        var captor = ArgumentCaptor.forClass(
                com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand.class);
        verify(assetService).createRelation(eq(PROJECT_ID), captor.capture(), eq(ASSET_ID));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().knowledgeState())
                .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);
    }
}
