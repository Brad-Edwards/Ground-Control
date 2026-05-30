package com.keplerops.groundcontrol.unit.domain.interchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.interchange.model.GrcInterchangeProvenance;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.AssetPayload;
import com.keplerops.groundcontrol.domain.interchange.repository.GrcInterchangeProvenanceRepository;
import com.keplerops.groundcontrol.domain.interchange.service.GrcInterchangeImporter;
import com.keplerops.groundcontrol.domain.interchange.state.InterchangeEntityKind;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrcInterchangeImporterTest {

    private ProjectService projectService;
    private OperationalAssetRepository assetRepository;
    private GrcInterchangeProvenanceRepository provenanceRepository;
    private GrcInterchangeImporter importer;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000900");
    private Project project;

    @BeforeEach
    void setup() {
        projectService = mock(ProjectService.class);
        assetRepository = mock(OperationalAssetRepository.class);
        provenanceRepository = mock(GrcInterchangeProvenanceRepository.class);
        importer = new GrcInterchangeImporter(projectService, assetRepository, provenanceRepository);
        project = new Project("ground-control", "Ground Control");
        TestUtil.setField(project, "id", PROJECT_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
    }

    private GrcInterchangeBundle bundle(String projectIdentifier, List<AssetPayload> assets) {
        return new GrcInterchangeBundle(
                GrcInterchangeBundle.CURRENT_VERSION,
                Instant.now(),
                projectIdentifier,
                assets,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    void rejectsProjectMismatch() {
        var b = bundle("other-project", List.of());
        assertThatThrownBy(() -> importer.importBundle(PROJECT_ID, b))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("projectIdentifier");
    }

    @Test
    void rejectsUnknownFormatVersion() {
        var b = new GrcInterchangeBundle(
                "99.9", Instant.now(), "ground-control", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> importer.importBundle(PROJECT_ID, b))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("formatVersion");
    }

    @Test
    void rejectsMissingFormatVersion() {
        var b = new GrcInterchangeBundle(
                null, Instant.now(), "ground-control", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> importer.importBundle(PROJECT_ID, b)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void newAssetIsCreatedAndProvenanceWritten() {
        var payload = new AssetPayload(
                "EXT-A",
                "Asset A",
                "SOFTWARE",
                null,
                "desc",
                "alice",
                "bob",
                "PROD",
                "HIGH",
                "external-system",
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-02-01T00:00:00Z"));

        when(assetRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "EXT-A"))
                .thenReturn(Optional.empty());
        when(assetRepository.save(any(OperationalAsset.class))).thenAnswer(inv -> {
            OperationalAsset a = inv.getArgument(0);
            TestUtil.setField(a, "id", UUID.randomUUID());
            return a;
        });
        when(provenanceRepository.findByProjectIdAndEntityKindAndExternalUid(
                        PROJECT_ID, InterchangeEntityKind.OPERATIONAL_ASSET, "EXT-A"))
                .thenReturn(Optional.empty());
        when(provenanceRepository.save(any(GrcInterchangeProvenance.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = importer.importBundle(PROJECT_ID, bundle("ground-control", List.of(payload)));

        assertThat(result.assetsCreated()).isEqualTo(1);
        assertThat(result.assetsUpdated()).isZero();
        assertThat(result.provenanceWritten()).isEqualTo(1);
        verify(assetRepository, atLeastOnce()).save(any(OperationalAsset.class));
        verify(provenanceRepository, atLeastOnce()).save(any(GrcInterchangeProvenance.class));
    }

    @Test
    void existingAssetIsUpdatedNotDuplicated() {
        var payload = new AssetPayload(
                "EXT-A", "Asset A (renamed)", null, null, null, null, null, null, null, null, null, null);
        var existing = new OperationalAsset(project, "EXT-A", "Asset A");
        var existingId = UUID.randomUUID();
        TestUtil.setField(existing, "id", existingId);

        when(assetRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "EXT-A"))
                .thenReturn(Optional.of(existing));
        when(assetRepository.save(any(OperationalAsset.class))).thenAnswer(inv -> inv.getArgument(0));
        when(provenanceRepository.findByProjectIdAndEntityKindAndExternalUid(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(provenanceRepository.save(any(GrcInterchangeProvenance.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = importer.importBundle(PROJECT_ID, bundle("ground-control", List.of(payload)));

        assertThat(result.assetsCreated()).isZero();
        assertThat(result.assetsUpdated()).isEqualTo(1);
        assertThat(existing.getName()).isEqualTo("Asset A (renamed)");
    }

    @Test
    void provenanceWrittenForOtherEntityKindsWithoutCreatingDomain() {
        var b = new GrcInterchangeBundle(
                GrcInterchangeBundle.CURRENT_VERSION,
                Instant.now(),
                "ground-control",
                List.of(),
                List.of(new GrcInterchangeBundle.RiskScenarioPayload("RS-1", "scenario", null, null, null, null, null)),
                List.of(new GrcInterchangeBundle.ControlPayload("C-1", "control", null, null, null, null, null)),
                List.of(new GrcInterchangeBundle.FindingPayload("F-1", "finding", null, null, null, null, null, null)),
                List.of(new GrcInterchangeBundle.EvidenceArtifactPayload(
                        "E-1", "evidence", null, null, null, null, null)));
        when(provenanceRepository.findByProjectIdAndEntityKindAndExternalUid(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(provenanceRepository.save(any(GrcInterchangeProvenance.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = importer.importBundle(PROJECT_ID, b);

        assertThat(result.assetsCreated()).isZero();
        assertThat(result.provenanceWritten()).isEqualTo(4);
        verify(assetRepository, never()).save(any(OperationalAsset.class));
    }

    @Test
    void rejectsBlankExternalUid() {
        var payload = new AssetPayload("  ", "Asset A", null, null, null, null, null, null, null, null, null, null);
        var blankUidBundle = bundle("ground-control", List.of(payload));
        assertThatThrownBy(() -> importer.importBundle(PROJECT_ID, blankUidBundle))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void provenanceIsRefreshedOnReimport() {
        var payload = new AssetPayload(
                "EXT-R",
                "Asset Reimport",
                "SOFTWARE",
                null,
                null,
                null,
                null,
                null,
                null,
                "sys-a",
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-06-01T00:00:00Z"));
        var existing = new OperationalAsset(project, "EXT-R", "Asset Reimport");
        var existingId = UUID.randomUUID();
        TestUtil.setField(existing, "id", existingId);

        when(assetRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "EXT-R"))
                .thenReturn(Optional.of(existing));
        when(assetRepository.save(any(OperationalAsset.class))).thenAnswer(inv -> inv.getArgument(0));

        // Existing provenance row - simulates a re-import
        var existingProv = new GrcInterchangeProvenance(
                project,
                InterchangeEntityKind.OPERATIONAL_ASSET,
                existingId,
                "EXT-R",
                new GrcInterchangeProvenance.ImportContext(
                        "sys-old",
                        Instant.parse("2020-01-01T00:00:00Z"),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        Instant.parse("2020-01-02T00:00:00Z"),
                        "carol"));
        when(provenanceRepository.findByProjectIdAndEntityKindAndExternalUid(
                        PROJECT_ID, InterchangeEntityKind.OPERATIONAL_ASSET, "EXT-R"))
                .thenReturn(Optional.of(existingProv));
        when(provenanceRepository.save(any(GrcInterchangeProvenance.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = importer.importBundle(PROJECT_ID, bundle("ground-control", List.of(payload)));

        // asset already existed: counts as updated, not created
        assertThat(result.assetsCreated()).isZero();
        assertThat(result.assetsUpdated()).isEqualTo(1);
        assertThat(result.provenanceWritten()).isEqualTo(1);
        // provenance row was refreshed with the new source system
        assertThat(existingProv.getSourceSystem()).isEqualTo("sys-a");
    }

    @Test
    void nullBundleIsRejected() {
        assertThatThrownBy(() -> importer.importBundle(PROJECT_ID, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("bundle must not be null");
    }

    @Test
    void emptyBundleImportsSuccessfully() {
        var b = GrcInterchangeBundle.empty("ground-control");
        var result = importer.importBundle(PROJECT_ID, b);

        assertThat(result.assetsCreated()).isZero();
        assertThat(result.assetsUpdated()).isZero();
        assertThat(result.provenanceWritten()).isZero();
    }
}
