package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.CreateObservationCommand;
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
class ObservationServiceTest {
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
    class Create {

        @Test
        void createsObservation() {
            when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            assetId, ObservationCategory.CONFIGURATION, "os_version", NOW))
                    .thenReturn(false);
            when(observationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION,
                    "os_version",
                    "Ubuntu 22.04",
                    "scanner-agent",
                    NOW,
                    NOW.plusSeconds(86400),
                    "HIGH",
                    "https://evidence.example.com/scan/123");

            var result = observationService.create(assetId, command);

            assertThat(result.getCategory()).isEqualTo(ObservationCategory.CONFIGURATION);
            assertThat(result.getObservationKey()).isEqualTo("os_version");
            assertThat(result.getObservationValue()).isEqualTo("Ubuntu 22.04");
            assertThat(result.getSource()).isEqualTo("scanner-agent");
            assertThat(result.getObservedAt()).isEqualTo(NOW);
            assertThat(result.getExpiresAt()).isEqualTo(NOW.plusSeconds(86400));
            assertThat(result.getConfidence()).isEqualTo("HIGH");
            assertThat(result.getEvidenceRef()).isEqualTo("https://evidence.example.com/scan/123");
        }

        @Test
        void throwsWhenAssetNotFound() {
            when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION, "os_version", "Ubuntu 22.04", "scanner", NOW, null, null, null);

            assertThatThrownBy(() -> observationService.create(assetId, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsOnDuplicate() {
            when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            assetId, ObservationCategory.CONFIGURATION, "os_version", NOW))
                    .thenReturn(true);

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION, "os_version", "Ubuntu 22.04", "scanner", NOW, null, null, null);

            assertThatThrownBy(() -> observationService.create(assetId, command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsWhenExpiresAtBeforeObservedAt() {
            when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            any(), any(), any(), any()))
                    .thenReturn(false);

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION,
                    "os_version",
                    "Ubuntu 22.04",
                    "scanner",
                    NOW,
                    NOW.minusSeconds(3600),
                    null,
                    null);

            assertThatThrownBy(() -> observationService.create(assetId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("expiresAt must be after observedAt");
        }

        @Test
        void createsWithNullOptionalFields() {
            when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            any(), any(), any(), any()))
                    .thenReturn(false);
            when(observationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateObservationCommand(
                    ObservationCategory.EXPOSURE, "cve_status", "vulnerable", "nessus", NOW, null, null, null);

            var result = observationService.create(assetId, command);

            assertThat(result.getExpiresAt()).isNull();
            assertThat(result.getConfidence()).isNull();
            assertThat(result.getEvidenceRef()).isNull();
        }
    }

    @Nested
    class Update {

        @Test
        void updatesObservation() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAsset(obsId)).thenReturn(Optional.of(obs));
            when(observationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateObservationCommand("Ubuntu 24.04", null, "MEDIUM", null);
            var result = observationService.update(assetId, obsId, command);

            assertThat(result.getObservationValue()).isEqualTo("Ubuntu 24.04");
            assertThat(result.getConfidence()).isEqualTo("MEDIUM");
        }

        @Test
        void throwsWhenObservationNotFound() {
            var obsId = UUID.randomUUID();
            when(observationRepository.findByIdWithAsset(obsId)).thenReturn(Optional.empty());

            var command = new UpdateObservationCommand("new value", null, null, null);

            assertThatThrownBy(() -> observationService.update(assetId, obsId, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsWhenObservationBelongsToDifferentAsset() {
            var obs = makeObservation();
            var obsId = obs.getId();
            var otherAssetId = UUID.randomUUID();
            when(observationRepository.findByIdWithAsset(obsId)).thenReturn(Optional.of(obs));

            var command = new UpdateObservationCommand("new value", null, null, null);

            assertThatThrownBy(() -> observationService.update(otherAssetId, obsId, command))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsObservation() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAsset(obsId)).thenReturn(Optional.of(obs));

            var result = observationService.getById(assetId, obsId);

            assertThat(result.getId()).isEqualTo(obsId);
        }
    }

    @Nested
    class ListByAsset {

        @Test
        void listsAllObservations() {
            when(assetRepository.existsById(assetId)).thenReturn(true);
            when(observationRepository.findByAssetId(assetId)).thenReturn(List.of(makeObservation()));

            var result = observationService.listByAsset(assetId, null, null);

            assertThat(result).hasSize(1);
        }

        @Test
        void filtersByCategory() {
            when(assetRepository.existsById(assetId)).thenReturn(true);
            when(observationRepository.findByAssetIdAndCategory(assetId, ObservationCategory.CONFIGURATION))
                    .thenReturn(List.of(makeObservation()));

            var result = observationService.listByAsset(assetId, ObservationCategory.CONFIGURATION, null);

            assertThat(result).hasSize(1);
        }

        @Test
        void filtersByKey() {
            when(assetRepository.existsById(assetId)).thenReturn(true);
            when(observationRepository.findByAssetIdAndKey(assetId, "os_version"))
                    .thenReturn(List.of(makeObservation()));

            var result = observationService.listByAsset(assetId, null, "os_version");

            assertThat(result).hasSize(1);
        }

        @Test
        void filtersByCategoryAndKey() {
            when(assetRepository.existsById(assetId)).thenReturn(true);
            when(observationRepository.findByAssetIdAndCategoryAndKey(
                            assetId, ObservationCategory.CONFIGURATION, "os_version"))
                    .thenReturn(List.of(makeObservation()));

            var result = observationService.listByAsset(assetId, ObservationCategory.CONFIGURATION, "os_version");

            assertThat(result).hasSize(1);
        }

        @Test
        void throwsWhenAssetNotFound() {
            when(assetRepository.existsById(assetId)).thenReturn(false);

            assertThatThrownBy(() -> observationService.listByAsset(assetId, null, null))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListLatest {

        @Test
        void returnsLatest() {
            when(assetRepository.existsById(assetId)).thenReturn(true);
            when(observationRepository.findLatestByAssetId(any(), any())).thenReturn(List.of(makeObservation()));

            var result = observationService.listLatest(assetId);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesObservation() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAsset(obsId)).thenReturn(Optional.of(obs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.OBSERVATION,
                            obsId,
                            projectId))
                    .thenReturn(java.util.List.of());

            observationService.delete(assetId, obsId);

            verify(observationRepository).delete(obs);
        }

        @Test
        void rejectsDeleteWhenInboundFindingLinkReferencesObservation() {
            var obs = makeObservation();
            var obsId = obs.getId();
            when(observationRepository.findByIdWithAsset(obsId)).thenReturn(Optional.of(obs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.OBSERVATION,
                            obsId,
                            projectId))
                    .thenReturn(java.util.List.of("FIND-001"));

            // FindingLink.targetEntityId is not an FK, so without this guard the
            // delete would leave dangling FindingLink rows (cycle-3 pre-push codex
            // review on issue #279, ADR-038). The non-project-scoped overload is
            // deprecated but its reverse-link guard must remain symmetric.
            @SuppressWarnings("deprecation")
            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    ConflictException.class, () -> observationService.delete(assetId, obsId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("FindingLink references exist")
                    .extracting("errorCode")
                    .isEqualTo("observation_referenced");
            org.mockito.Mockito.verify(observationRepository, org.mockito.Mockito.never())
                    .delete(obs);
        }
    }

    @Nested
    class ProjectAwareCreate {

        @Test
        void createsObservationWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            assetId, ObservationCategory.CONFIGURATION, "os_version", NOW))
                    .thenReturn(false);
            when(observationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION,
                    "os_version",
                    "Ubuntu 22.04",
                    "scanner-agent",
                    NOW,
                    NOW.plusSeconds(86400),
                    "HIGH",
                    "https://evidence.example.com/scan/123");

            var result = observationService.create(projectId, assetId, command);

            assertThat(result.getCategory()).isEqualTo(ObservationCategory.CONFIGURATION);
            assertThat(result.getObservationKey()).isEqualTo("os_version");
            assertThat(result.getObservationValue()).isEqualTo("Ubuntu 22.04");
            assertThat(result.getSource()).isEqualTo("scanner-agent");
            assertThat(result.getExpiresAt()).isEqualTo(NOW.plusSeconds(86400));
            assertThat(result.getConfidence()).isEqualTo("HIGH");
            assertThat(result.getEvidenceRef()).isEqualTo("https://evidence.example.com/scan/123");
        }

        @Test
        void throwsWhenAssetNotFoundInProject() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.empty());

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION, "os_version", "Ubuntu 22.04", "scanner", NOW, null, null, null);

            assertThatThrownBy(() -> observationService.create(projectId, assetId, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsOnDuplicateWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            assetId, ObservationCategory.CONFIGURATION, "os_version", NOW))
                    .thenReturn(true);

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION, "os_version", "Ubuntu 22.04", "scanner", NOW, null, null, null);

            assertThatThrownBy(() -> observationService.create(projectId, assetId, command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsWhenExpiresAtBeforeObservedAtWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            any(), any(), any(), any()))
                    .thenReturn(false);

            var command = new CreateObservationCommand(
                    ObservationCategory.CONFIGURATION,
                    "os_version",
                    "Ubuntu 22.04",
                    "scanner",
                    NOW,
                    NOW.minusSeconds(3600),
                    null,
                    null);

            assertThatThrownBy(() -> observationService.create(projectId, assetId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("expiresAt must be after observedAt");
        }

        @Test
        void createsWithNullOptionalFieldsWithProjectId() {
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(observationRepository.existsByAssetIdAndCategoryAndObservationKeyAndObservedAt(
                            any(), any(), any(), any()))
                    .thenReturn(false);
            when(observationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateObservationCommand(
                    ObservationCategory.EXPOSURE, "cve_status", "vulnerable", "nessus", NOW, null, null, null);

            var result = observationService.create(projectId, assetId, command);

            assertThat(result.getExpiresAt()).isNull();
            assertThat(result.getConfidence()).isNull();
            assertThat(result.getEvidenceRef()).isNull();
        }
    }
}
