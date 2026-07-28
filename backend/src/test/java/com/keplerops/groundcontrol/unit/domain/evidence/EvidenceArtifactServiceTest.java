package com.keplerops.groundcontrol.unit.domain.evidence;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.service.CreateEvidenceArtifactCommand;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceArtifactService;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.verification.repository.VerificationResultRepository;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
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

/** Split from EvidenceArtifactServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class EvidenceArtifactServiceTest {
    @Mock
    private EvidenceArtifactRepository repository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private ControlTestRepository controlTestRepository;

    @Mock
    private VerificationResultRepository verificationResultRepository;

    @Mock
    private FindingRepository findingRepository;

    @InjectMocks
    private EvidenceArtifactService service;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private CreateEvidenceArtifactCommand happyCommandWithSources(List<EvidenceSourceRef> sources) {
        return new CreateEvidenceArtifactCommand(
                projectId,
                "EVD-0001",
                "Q2 assurance summary",
                "Control X operated effectively across Q2.",
                EvidenceType.ASSURANCE_CONCLUSION,
                "manual-rollup-v1",
                Instant.parse("2026-04-30T17:00:00Z"),
                AssuranceLevel.L1,
                "high",
                null,
                sources);
    }

    private EvidenceArtifact buildArtifact(String uid) {
        return new EvidenceArtifact(
                project,
                uid,
                "title-" + uid,
                "summary-" + uid,
                EvidenceType.ATTESTATION,
                "method-v1",
                Instant.parse("2026-04-30T17:00:00Z"));
    }

    @Nested
    class Create {

        @Test
        void persistsArtifactWithInternalObservationSource() {
            var observationId = UUID.randomUUID();
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);
            when(observationRepository.existsByIdAndProjectId(observationId, projectId))
                    .thenReturn(true);
            when(repository.save(any(EvidenceArtifact.class))).thenAnswer(inv -> inv.getArgument(0));

            var sources =
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, observationId, null, "primary"));
            var result = service.create(happyCommandWithSources(sources));

            assertThat(result.getUid()).isEqualTo("EVD-0001");
            assertThat(result.getEvidenceType()).isEqualTo(EvidenceType.ASSURANCE_CONCLUSION);
            assertThat(result.getDerivationMethod()).isEqualTo("manual-rollup-v1");
            assertThat(result.getAssuranceLevel()).isEqualTo(AssuranceLevel.L1);
            assertThat(result.getSources()).hasSize(1);
            assertThat(result.getSources().get(0).sourceKind()).isEqualTo(EvidenceSourceKind.OBSERVATION);
            assertThat(result.getSupersededByArtifactId()).isNull();
            verify(repository).save(any(EvidenceArtifact.class));
        }

        @Test
        void allowsExternalAttestationSourceWithIdentifier() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);
            when(repository.save(any(EvidenceArtifact.class))).thenAnswer(inv -> inv.getArgument(0));

            var sources =
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "vendor-soc2-2026", "primary"));
            var result = service.create(happyCommandWithSources(sources));

            assertThat(result.getSources()).hasSize(1);
            assertThat(result.getSources().get(0).sourceIdentifier()).isEqualTo("vendor-soc2-2026");
        }

        @Test
        void rejectsDuplicateUidInSameProject() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(true);

            var sources =
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "vendor-soc2-2026", null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("EVD-0001");
            verify(repository, never()).save(any());
        }

        @Test
        void rejectsEmptySourcesList() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);

            var command = happyCommandWithSources(List.of());
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("at least one source");
            verify(repository, never()).save(any());
        }

        @Test
        void rejectsInternalSourceMissingEntityId() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);

            var sources = List.of(new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, null, null, null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("sourceEntityId is required");
        }

        @Test
        void rejectsInternalSourceCarryingIdentifier() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);

            var sources = List.of(
                    new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, UUID.randomUUID(), "stray-identifier", null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("sourceIdentifier must be null for internal source kind");
        }

        @Test
        void rejectsExternalSourceMissingIdentifier() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);

            var sources = List.of(new EvidenceSourceRef(EvidenceSourceKind.EXTERNAL, null, "", null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("sourceIdentifier is required");
        }

        @Test
        void rejectsExternalSourceCarryingEntityId() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);

            var sources = List.of(new EvidenceSourceRef(EvidenceSourceKind.EXTERNAL, UUID.randomUUID(), "ident", null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("sourceEntityId must be null for external source kind");
        }

        @Test
        void rejectsInternalSourceWhenTargetMissingInProject() {
            var missingId = UUID.randomUUID();
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);
            when(observationRepository.existsByIdAndProjectId(missingId, projectId))
                    .thenReturn(false);

            var sources = List.of(new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, missingId, null, null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("OBSERVATION/" + missingId);
        }

        @Test
        void resolvesMixedInternalKinds() {
            var ctrlTestId = UUID.randomUUID();
            var verificationId = UUID.randomUUID();
            var findingId = UUID.randomUUID();
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);
            when(controlTestRepository.findByIdAndProjectId(ctrlTestId, projectId))
                    .thenReturn(Optional.of(mock(com.keplerops.groundcontrol.domain.controls.model.ControlTest.class)));
            when(verificationResultRepository.existsByIdAndProjectId(verificationId, projectId))
                    .thenReturn(true);
            when(findingRepository.existsByIdAndProjectId(findingId, projectId)).thenReturn(true);
            when(repository.save(any(EvidenceArtifact.class))).thenAnswer(inv -> inv.getArgument(0));

            var sources = List.of(
                    new EvidenceSourceRef(EvidenceSourceKind.CONTROL_TEST, ctrlTestId, null, null),
                    new EvidenceSourceRef(EvidenceSourceKind.VERIFICATION_RESULT, verificationId, null, null),
                    new EvidenceSourceRef(EvidenceSourceKind.FINDING, findingId, null, null),
                    new EvidenceSourceRef(EvidenceSourceKind.EXTERNAL, null, "external-id-123", null));

            var result = service.create(happyCommandWithSources(sources));

            assertThat(result.getSources()).hasSize(4);
            // Per-source identity assertions so a regression in validateSources
            // that mismaps kinds or scrambles list order is caught directly
            // (rather than passing on size alone).
            assertThat(result.getSources())
                    .extracting(EvidenceSourceRef::sourceKind)
                    .containsExactly(
                            EvidenceSourceKind.CONTROL_TEST,
                            EvidenceSourceKind.VERIFICATION_RESULT,
                            EvidenceSourceKind.FINDING,
                            EvidenceSourceKind.EXTERNAL);
            assertThat(result.getSources().get(0).sourceEntityId()).isEqualTo(ctrlTestId);
            assertThat(result.getSources().get(3).sourceIdentifier()).isEqualTo("external-id-123");
            verify(controlTestRepository).findByIdAndProjectId(ctrlTestId, projectId);
            verify(verificationResultRepository).existsByIdAndProjectId(verificationId, projectId);
            verify(findingRepository).existsByIdAndProjectId(findingId, projectId);
        }

        @Test
        void rejectsRetiredSourceKinds() {
            // ADR-089: CONTROL_EFFECTIVENESS_ASSESSMENT and RISK_ASSESSMENT_RESULT are retired
            // source kinds — their backing aggregates are gone, so no new source can resolve
            // against them even though the enum constants remain for historical JSON reads.
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0001")).thenReturn(false);

            var entityId = UUID.randomUUID();
            var sources = List.of(
                    new EvidenceSourceRef(EvidenceSourceKind.CONTROL_EFFECTIVENESS_ASSESSMENT, entityId, null, null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("CONTROL_EFFECTIVENESS_ASSESSMENT/" + entityId);
        }
    }

    @Nested
    class Supersede {

        @Test
        void persistsNewArtifactAndLinksPrior() {
            var priorId = UUID.randomUUID();
            var observationId = UUID.randomUUID();
            var prior = buildArtifact("EVD-0001");
            setField(prior, "id", priorId);
            when(repository.findByIdAndProjectId(priorId, projectId)).thenReturn(Optional.of(prior));
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0002")).thenReturn(false);
            when(observationRepository.existsByIdAndProjectId(observationId, projectId))
                    .thenReturn(true);
            when(repository.save(any(EvidenceArtifact.class))).thenAnswer(inv -> {
                EvidenceArtifact arg = inv.getArgument(0);
                if (arg.getUid().equals("EVD-0002") && arg.getId() == null) {
                    setField(arg, "id", UUID.randomUUID());
                }
                return arg;
            });
            when(repository.markSupersededIfUnset(any(UUID.class), any(UUID.class), any(UUID.class)))
                    .thenReturn(1);

            var replacement = service.supersede(
                    projectId,
                    priorId,
                    new CreateEvidenceArtifactCommand(
                            projectId,
                            "EVD-0002",
                            "Revised summary",
                            "After re-test, conclusion adjusted.",
                            EvidenceType.ASSURANCE_CONCLUSION,
                            "manual-rollup-v2",
                            Instant.parse("2026-05-15T17:00:00Z"),
                            AssuranceLevel.L1,
                            "high",
                            null,
                            List.of(new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, observationId, null, null))));

            assertThat(replacement.getUid()).isEqualTo("EVD-0002");
            verify(repository).markSupersededIfUnset(priorId, projectId, replacement.getId());
        }

        @Test
        void supersedeLosesRaceWhenConditionalUpdateAffectsZeroRows() {
            // Codex finding (cycle 1): two concurrent supersedes against the
            // same prior must not both succeed. The loser observes
            // markSupersededIfUnset() returning 0 and surfaces the same
            // already-superseded conflict the first call would have produced.
            var priorId = UUID.randomUUID();
            var observationId = UUID.randomUUID();
            var prior = buildArtifact("EVD-0001");
            setField(prior, "id", priorId);
            var refreshedPrior = buildArtifact("EVD-0001");
            setField(refreshedPrior, "id", priorId);
            refreshedPrior.setSupersededByArtifactId(UUID.randomUUID());
            when(repository.findByIdAndProjectId(priorId, projectId))
                    .thenReturn(Optional.of(prior))
                    .thenReturn(Optional.of(refreshedPrior));
            when(projectService.getById(projectId)).thenReturn(project);
            when(repository.existsByProjectIdAndUid(projectId, "EVD-0002")).thenReturn(false);
            when(observationRepository.existsByIdAndProjectId(observationId, projectId))
                    .thenReturn(true);
            when(repository.save(any(EvidenceArtifact.class))).thenAnswer(inv -> {
                EvidenceArtifact arg = inv.getArgument(0);
                if (arg.getUid().equals("EVD-0002") && arg.getId() == null) {
                    setField(arg, "id", UUID.randomUUID());
                }
                return arg;
            });
            when(repository.markSupersededIfUnset(any(UUID.class), any(UUID.class), any(UUID.class)))
                    .thenReturn(0);

            var command = new CreateEvidenceArtifactCommand(
                    projectId,
                    "EVD-0002",
                    "Revised",
                    "Y",
                    EvidenceType.ASSURANCE_CONCLUSION,
                    "manual-rollup-v2",
                    Instant.parse("2026-05-15T17:00:00Z"),
                    null,
                    null,
                    null,
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, observationId, null, null)));
            assertThatThrownBy(() -> service.supersede(projectId, priorId, command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already superseded");
        }

        @Test
        void rejectsSecondSupersedeOnAlreadySupersededArtifact() {
            var priorId = UUID.randomUUID();
            var prior = buildArtifact("EVD-0001");
            setField(prior, "id", priorId);
            prior.setSupersededByArtifactId(UUID.randomUUID());
            when(repository.findByIdAndProjectId(priorId, projectId)).thenReturn(Optional.of(prior));

            var sources =
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "vendor-soc2-2026", null));
            var commandForRetry = new CreateEvidenceArtifactCommand(
                    projectId,
                    "EVD-0003",
                    "retry",
                    "retry-summary",
                    EvidenceType.ATTESTATION,
                    "method-v2",
                    Instant.parse("2026-05-15T17:00:00Z"),
                    null,
                    null,
                    null,
                    sources);
            assertThatThrownBy(() -> service.supersede(projectId, priorId, commandForRetry))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already superseded");
            verify(repository, never()).save(any());
        }

        @Test
        void notFoundWhenPriorMissing() {
            var priorId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(priorId, projectId)).thenReturn(Optional.empty());

            var sources =
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "vendor-soc2-2026", null));
            var command = happyCommandWithSources(sources);
            assertThatThrownBy(() -> service.supersede(projectId, priorId, command))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void rejectsSupersedeWithMismatchedProjectId() {
            var priorId = UUID.randomUUID();
            var prior = buildArtifact("EVD-0001");
            setField(prior, "id", priorId);
            when(repository.findByIdAndProjectId(priorId, projectId)).thenReturn(Optional.of(prior));

            var sources =
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "vendor-soc2-2026", null));
            var commandWithDifferentProject = new CreateEvidenceArtifactCommand(
                    UUID.randomUUID(),
                    "EVD-0002",
                    "x",
                    "y",
                    EvidenceType.ATTESTATION,
                    "m",
                    Instant.parse("2026-05-15T17:00:00Z"),
                    null,
                    null,
                    null,
                    sources);
            assertThatThrownBy(() -> service.supersede(projectId, priorId, commandWithDifferentProject))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("projectId");
        }
    }

    @Nested
    class List_ {

        @Test
        void includeSupersededFalseFiltersChainedArtifacts() {
            var a = buildArtifact("EVD-0001");
            setField(a, "id", UUID.randomUUID());
            a.setSupersededByArtifactId(UUID.randomUUID());
            var b = buildArtifact("EVD-0002");
            setField(b, "id", UUID.randomUUID());

            when(repository.findByProjectIdOrderByDerivedAtDesc(projectId)).thenReturn(java.util.List.of(b, a));

            var rows = service.listByProject(projectId, null, false);

            assertThat(rows).extracting(EvidenceArtifact::getUid).containsExactly("EVD-0002");
        }

        @Test
        void includeSupersededTrueReturnsEverything() {
            var a = buildArtifact("EVD-0001");
            setField(a, "id", UUID.randomUUID());
            a.setSupersededByArtifactId(UUID.randomUUID());
            var b = buildArtifact("EVD-0002");
            setField(b, "id", UUID.randomUUID());

            when(repository.findByProjectIdOrderByDerivedAtDesc(projectId)).thenReturn(java.util.List.of(b, a));

            var rows = service.listByProject(projectId, null, true);

            assertThat(rows).hasSize(2);
        }

        @Test
        void filtersByEvidenceType() {
            var a = buildArtifact("EVD-0001");
            setField(a, "id", UUID.randomUUID());
            when(repository.findByProjectIdAndEvidenceTypeOrderByDerivedAtDesc(projectId, EvidenceType.ATTESTATION))
                    .thenReturn(java.util.List.of(a));

            var rows = service.listByProject(projectId, EvidenceType.ATTESTATION, false);

            assertThat(rows).hasSize(1);
            verify(repository).findByProjectIdAndEvidenceTypeOrderByDerivedAtDesc(projectId, EvidenceType.ATTESTATION);
        }
    }
}
