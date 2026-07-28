package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.repository.ControlLinkRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.service.FindingService;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import java.time.Instant;
import java.time.LocalDate;
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

/** Split from FindingServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class FindingServiceDeleteTest {
    @Mock
    private FindingRepository findingRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository findingLinkRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private AssetLinkRepository assetLinkRepository;

    @Mock
    private ControlLinkRepository controlLinkRepository;

    @Mock
    private RiskScenarioLinkRepository riskScenarioLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository
            threatModelLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository auditLinkRepository;

    @InjectMocks
    private FindingService findingService;

    private Project project;
    private UUID projectId;
    private static final Instant NOW = Instant.parse("2026-05-13T12:00:00Z");
    private static final LocalDate DUE = LocalDate.of(2026, 6, 30);

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private com.keplerops.groundcontrol.domain.findings.model.Finding makeFinding() {
        var f = new com.keplerops.groundcontrol.domain.findings.model.Finding(
                project,
                "FIND-001",
                "MFA missing on admin portal",
                FindingType.CONTROL_DEFICIENCY,
                FindingSeverity.HIGH,
                "Admin portal accepts password-only auth.");
        f.setRootCauseAnalysis("Identity provider misconfigured during migration.");
        f.setOwner("alice");
        f.setDueDate(DUE);
        f.setCreatedBy("analyst");
        setField(f, "id", UUID.randomUUID());
        setField(f, "createdAt", NOW);
        setField(f, "updatedAt", NOW);
        return f;
    }

    @Nested
    class Delete {

        @Test
        void deletesFindingWhenNoReverseLinks() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(controlLinkRepository.findControlUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            ControlLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(findingLinkRepository.findByFindingId(f.getId())).thenReturn(List.of());

            findingService.delete(projectId, f.getId());

            verify(findingRepository).delete(f);
        }

        @Test
        void deletesOutboundLinksThroughRepositoryBeforeParent() {
            var f = makeFinding();
            var outboundLinks = List.of(
                    new com.keplerops.groundcontrol.domain.findings.model.FindingLink(
                            f,
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.CONTROL,
                            UUID.randomUUID(),
                            null,
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkType.MITIGATED_BY),
                    new com.keplerops.groundcontrol.domain.findings.model.FindingLink(
                            f,
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.EVIDENCE,
                            null,
                            "s3://evidence/x",
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkType.EVIDENCED_BY));
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(controlLinkRepository.findControlUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            ControlLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(findingLinkRepository.findByFindingId(f.getId())).thenReturn(outboundLinks);

            findingService.delete(projectId, f.getId());

            // Envers writes delete revisions only when Hibernate sees the link
            // delete. Driving the deletes through findingLinkRepository.deleteAll
            // is the contract that closes the parent-delete audit-history gap
            // (cycle-2 pre-push codex review on issue #279, ADR-038).
            var inOrder = org.mockito.Mockito.inOrder(findingLinkRepository, findingRepository);
            inOrder.verify(findingLinkRepository).deleteAll(outboundLinks);
            inOrder.verify(findingRepository).delete(f);
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(findingRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> findingService.delete(projectId, id)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsDeleteWhenAssetLinkReferencesFinding() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of("ASSET-A", "ASSET-B"));
            when(controlLinkRepository.findControlUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            ControlLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());

            var fId = f.getId();
            var thrown = catchThrowableOfType(ConflictException.class, () -> findingService.delete(projectId, fId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("reverse links")
                    .extracting("errorCode")
                    .isEqualTo("finding_referenced");
            assertThat(thrown.getDetail())
                    .containsEntry("findingUid", f.getUid())
                    .containsEntry("assetCount", 2)
                    .containsEntry("controlCount", 0)
                    .containsEntry("scenarioCount", 0)
                    .containsEntry("assetUids", (java.io.Serializable) List.of("ASSET-A", "ASSET-B"));
        }

        @Test
        void rejectsDeleteWhenControlLinkReferencesFinding() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(controlLinkRepository.findControlUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            ControlLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of("CTRL-1"));
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());

            var fId = f.getId();
            var thrown = catchThrowableOfType(ConflictException.class, () -> findingService.delete(projectId, fId));
            assertThat(thrown).isNotNull().extracting("errorCode").isEqualTo("finding_referenced");
            assertThat(thrown.getDetail())
                    .containsEntry("controlCount", 1)
                    .containsEntry("controlUids", (java.io.Serializable) List.of("CTRL-1"));
        }

        @Test
        void rejectsDeleteWhenAuditLinkReferencesFinding() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(controlLinkRepository.findControlUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            ControlLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(auditLinkRepository.findAuditUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType.FINDING,
                            f.getId(),
                            projectId))
                    .thenReturn(List.of("AUDIT-001"));

            var fId = f.getId();
            var thrown = catchThrowableOfType(ConflictException.class, () -> findingService.delete(projectId, fId));
            assertThat(thrown).isNotNull().extracting("errorCode").isEqualTo("finding_referenced");
            assertThat(thrown.getDetail())
                    .containsEntry("auditCount", 1)
                    .containsEntry("auditUids", (java.io.Serializable) List.of("AUDIT-001"));
        }

        @Test
        void rejectsDeleteWhenRiskScenarioLinkReferencesFinding() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(controlLinkRepository.findControlUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            ControlLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of("RS-001"));

            var fId = f.getId();
            var thrown = catchThrowableOfType(ConflictException.class, () -> findingService.delete(projectId, fId));
            assertThat(thrown).isNotNull().extracting("errorCode").isEqualTo("finding_referenced");
            assertThat(thrown.getDetail())
                    .containsEntry("scenarioCount", 1)
                    .containsEntry("scenarioUids", (java.io.Serializable) List.of("RS-001"));
        }

        @Test
        void rejectsDeleteWhenThreatModelLinkReferencesFinding() {
            // GC-H009: a vulnerability finding linked to a threat model is an
            // inbound reference too. Deleting the finding without removing the
            // ThreatModelLink would leave a dangling THREAT_MODEL -> FINDING
            // edge in the graph projection.
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(controlLinkRepository.findControlUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            ControlLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.FINDING, f.getId(), projectId))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findThreatModelUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType.FINDING,
                            f.getId(),
                            projectId))
                    .thenReturn(List.of("TM-7", "TM-9"));

            var fId = f.getId();
            var thrown = catchThrowableOfType(ConflictException.class, () -> findingService.delete(projectId, fId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("ThreatModelLink")
                    .extracting("errorCode")
                    .isEqualTo("finding_referenced");
            assertThat(thrown.getDetail())
                    .containsEntry("threatModelCount", 2)
                    .containsEntry("threatModelUids", (java.io.Serializable) List.of("TM-7", "TM-9"));
        }
    }
}
