package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.ObservationService;
import com.keplerops.groundcontrol.domain.assets.service.UpdateObservationCommand;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from ObservationServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class ObservationServiceProjectAwareUpdateTest {
    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private OperationalAssetRepository assetRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository findingLinkRepository;

    @InjectMocks
    private ObservationService observationService;

    private OperationalAsset asset;
    private UUID assetId;
    private UUID projectId;
    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        var project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        asset = new OperationalAsset(project, "WEB-001", "Web Server");
        assetId = UUID.randomUUID();
        setField(asset, "id", assetId);
    }

    private Observation makeObservation() {
        var obs = new Observation(
                asset, ObservationCategory.CONFIGURATION, "os_version", "Ubuntu 22.04", "scanner-agent", NOW);
        obs.setExpiresAt(NOW.plusSeconds(86400));
        obs.setConfidence("HIGH");
        obs.setEvidenceRef("https://evidence.example.com/scan/123");
        setField(obs, "id", UUID.randomUUID());
        setField(obs, "createdAt", NOW);
        setField(obs, "updatedAt", NOW);
        return obs;
    }

    @Nested
    class ProjectAwareUpdate {

        @Test
        void updatesObservationWithProjectId() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));
            when(observationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateObservationCommand("Ubuntu 24.04", null, "MEDIUM", null);
            var result = observationService.update(projectId, assetId, obsId, command);

            assertThat(result.getObservationValue()).isEqualTo("Ubuntu 24.04");
            assertThat(result.getConfidence()).isEqualTo("MEDIUM");
        }

        @Test
        void updatesAllFieldsWithProjectId() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));
            when(observationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var newExpiry = NOW.plusSeconds(172800);
            var command =
                    new UpdateObservationCommand("new-value", newExpiry, "LOW", "https://new-evidence.example.com");
            var result = observationService.update(projectId, assetId, obsId, command);

            assertThat(result.getObservationValue()).isEqualTo("new-value");
            assertThat(result.getExpiresAt()).isEqualTo(newExpiry);
            assertThat(result.getConfidence()).isEqualTo("LOW");
            assertThat(result.getEvidenceRef()).isEqualTo("https://new-evidence.example.com");
        }

        @Test
        void throwsWhenExpiresAtBeforeObservedAtOnUpdate() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));

            var command = new UpdateObservationCommand(null, NOW.minusSeconds(3600), null, null);

            assertThatThrownBy(() -> observationService.update(projectId, assetId, obsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("expiresAt must be after observedAt");
        }

        @Test
        void throwsWhenObservationNotFoundWithProjectId() {
            var obsId = UUID.randomUUID();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.empty());

            var command = new UpdateObservationCommand("new value", null, null, null);

            assertThatThrownBy(() -> observationService.update(projectId, assetId, obsId, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsWhenObservationBelongsToDifferentAssetWithProjectId() {
            var obs = makeObservation();
            var obsId = obs.getId();
            var otherAssetId = UUID.randomUUID();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));

            var command = new UpdateObservationCommand("new value", null, null, null);

            assertThatThrownBy(() -> observationService.update(projectId, otherAssetId, obsId, command))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ProjectAwareGetById {

        @Test
        void returnsObservationWithProjectId() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));

            var result = observationService.getById(projectId, assetId, obsId);

            assertThat(result.getId()).isEqualTo(obsId);
        }

        @Test
        void throwsWhenNotFoundWithProjectId() {
            var obsId = UUID.randomUUID();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> observationService.getById(projectId, assetId, obsId))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsWhenBelongsToDifferentAssetWithProjectId() {
            var obs = makeObservation();
            var obsId = obs.getId();
            var otherAssetId = UUID.randomUUID();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));

            assertThatThrownBy(() -> observationService.getById(projectId, otherAssetId, obsId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ProjectAwareListByAsset {

        @Test
        void listsAllObservationsWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.findByAssetId(assetId)).thenReturn(List.of(makeObservation()));

            var result = observationService.listByAsset(projectId, assetId, null, null);

            assertThat(result).hasSize(1);
        }

        @Test
        void filtersByCategoryWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.findByAssetIdAndCategory(assetId, ObservationCategory.CONFIGURATION))
                    .thenReturn(List.of(makeObservation()));

            var result = observationService.listByAsset(projectId, assetId, ObservationCategory.CONFIGURATION, null);

            assertThat(result).hasSize(1);
        }

        @Test
        void filtersByKeyWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.findByAssetIdAndKey(assetId, "os_version"))
                    .thenReturn(List.of(makeObservation()));

            var result = observationService.listByAsset(projectId, assetId, null, "os_version");

            assertThat(result).hasSize(1);
        }

        @Test
        void filtersByCategoryAndKeyWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.findByAssetIdAndCategoryAndKey(
                            assetId, ObservationCategory.CONFIGURATION, "os_version"))
                    .thenReturn(List.of(makeObservation()));

            var result =
                    observationService.listByAsset(projectId, assetId, ObservationCategory.CONFIGURATION, "os_version");

            assertThat(result).hasSize(1);
        }

        @Test
        void throwsWhenAssetNotFoundWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> observationService.listByAsset(projectId, assetId, null, null))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ProjectAwareListLatest {

        @Test
        void returnsLatestWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.findLatestByAssetId(any(), any())).thenReturn(List.of(makeObservation()));

            var result = observationService.listLatest(projectId, assetId);

            assertThat(result).hasSize(1);
        }

        @Test
        void throwsWhenAssetNotFoundForLatest() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> observationService.listLatest(projectId, assetId))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsWhenAssetNotFoundForLegacyLatest() {
            when(assetRepository.existsById(assetId)).thenReturn(false);

            assertThatThrownBy(() -> observationService.listLatest(assetId)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ProjectAwareDelete {

        @Test
        void deletesObservationWithProjectId() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.OBSERVATION,
                            obsId,
                            projectId))
                    .thenReturn(java.util.List.of());

            observationService.delete(projectId, assetId, obsId);

            verify(observationRepository).delete(obs);
        }

        @Test
        void rejectsProjectAwareDeleteWhenInboundFindingLinkReferencesObservation() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.OBSERVATION,
                            obsId,
                            projectId))
                    .thenReturn(java.util.List.of("FIND-001"));

            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    ConflictException.class, () -> observationService.delete(projectId, assetId, obsId));
            assertThat(thrown).isNotNull().extracting("errorCode").isEqualTo("observation_referenced");
            verify(observationRepository, never()).delete(obs);
        }

        @Test
        void throwsWhenObservationNotFoundForDeleteWithProjectId() {
            var obsId = UUID.randomUUID();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> observationService.delete(projectId, assetId, obsId))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsWhenObservationBelongsToDifferentAssetForDelete() {
            var obs = makeObservation();
            var obsId = obs.getId();
            var otherAssetId = UUID.randomUUID();
            when(observationRepository.findByIdWithAssetAndProjectId(obsId, projectId))
                    .thenReturn(Optional.of(obs));

            assertThatThrownBy(() -> observationService.delete(projectId, otherAssetId, obsId))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
