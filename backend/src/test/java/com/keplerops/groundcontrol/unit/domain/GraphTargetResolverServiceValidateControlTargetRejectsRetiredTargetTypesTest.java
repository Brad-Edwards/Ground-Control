package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.audits.repository.AuditRepository;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.verification.repository.VerificationResultRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from GraphTargetResolverServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class GraphTargetResolverServiceValidateControlTargetRejectsRetiredTargetTypesTest {
    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private OperationalAssetRepository assetRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.controls.repository.ControlRepository controlRepository;

    @Mock
    private ThreatModelRepository threatModelRepository;

    @Mock
    private VerificationResultRepository verificationResultRepository;

    @Mock
    private FindingRepository findingRepository;

    @Mock
    private AuditRepository auditRepository;

    @Mock
    private EvidenceArtifactRepository evidenceArtifactRepository;

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private GraphTargetResolverService graphTargetResolverService;

    private final UUID projectId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    private void stubFindingInternalTarget(FindingLinkTargetType targetType, boolean exists) {
        switch (targetType) {
            case CONTROL -> when(controlRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case RISK_SCENARIO -> when(riskScenarioRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case ASSET -> when(assetRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case OBSERVATION -> when(observationRepository.findByIdWithAssetAndProjectId(targetId, projectId))
                    .thenReturn(java.util.Optional.of(
                            mock(com.keplerops.groundcontrol.domain.assets.model.Observation.class)));
            case AUDIT -> when(auditRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case EVIDENCE -> when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(mock(
                                            com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact.class))
                                    : java.util.Optional.empty());
            case OPERATIONAL_ARTIFACT, REMEDIATION_PLAN, EXTERNAL -> throw new IllegalArgumentException(
                    "Not an internal target type");
        }
    }

    private void stubAuditInternalTarget(AuditLinkTargetType targetType, boolean exists) {
        switch (targetType) {
            case ASSET -> when(assetRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case CONTROL -> when(controlRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case RISK_SCENARIO -> when(riskScenarioRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case EVIDENCE -> when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(mock(
                                            com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact.class))
                                    : java.util.Optional.empty());
            case FINDING -> when(findingRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case RISK_REGISTER_RECORD, FRAMEWORK, EXTERNAL -> throw new IllegalArgumentException(
                    "Not an internal target type");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ControlLinkTargetType.class,
            names = {"RISK_REGISTER_RECORD", "RISK_ASSESSMENT_RESULT", "TREATMENT_PLAN", "METHODOLOGY_PROFILE"})
    void validateControlTargetRejectsRetiredTargetTypes(ControlLinkTargetType targetType) {
        assertThatThrownBy(
                        () -> graphTargetResolverService.validateControlTarget(projectId, targetType, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("retired");
    }

    @Test
    void validateControlTargetRejectsMissingInternalTargetEntityId() {
        assertThatThrownBy(() -> graphTargetResolverService.validateControlTarget(
                        projectId, ControlLinkTargetType.ASSET, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetEntityId");
    }

    @Test
    void validateControlTargetRejectsMissingExternalIdentifier() {
        assertThatThrownBy(() -> graphTargetResolverService.validateControlTarget(
                        projectId, ControlLinkTargetType.EXTERNAL, null, " "))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetIdentifier");
    }

    @ParameterizedTest
    @EnumSource(
            value = FindingLinkTargetType.class,
            names = {"CONTROL", "RISK_SCENARIO", "ASSET", "OBSERVATION", "AUDIT", "EVIDENCE"})
    void validateFindingTargetAcceptsInternalTargets(FindingLinkTargetType targetType) {
        stubFindingInternalTarget(targetType, true);

        var validated = graphTargetResolverService.validateFindingTarget(projectId, targetType, targetId, null);

        assertThat(validated.internal()).isTrue();
        assertThat(validated.targetEntityId()).isEqualTo(targetId);
    }

    @ParameterizedTest
    @EnumSource(
            value = FindingLinkTargetType.class,
            names = {"OPERATIONAL_ARTIFACT", "REMEDIATION_PLAN", "EXTERNAL"})
    void validateFindingTargetAcceptsExternalTargets(FindingLinkTargetType targetType) {
        var validated = graphTargetResolverService.validateFindingTarget(projectId, targetType, null, "EXT-F");

        assertThat(validated.internal()).isFalse();
        assertThat(validated.targetEntityId()).isNull();
        assertThat(validated.targetIdentifier()).isEqualTo("EXT-F");
    }

    @Test
    void validateFindingTargetRejectsMissingExternalIdentifier() {
        assertThatThrownBy(() -> graphTargetResolverService.validateFindingTarget(
                        projectId, FindingLinkTargetType.EXTERNAL, null, " "))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetIdentifier");
    }

    @Test
    void validateFindingTargetRejectsMissingInternalTargetEntityId() {
        assertThatThrownBy(() -> graphTargetResolverService.validateFindingTarget(
                        projectId, FindingLinkTargetType.CONTROL, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetEntityId");
    }

    @Test
    void validateFindingTargetRejectsCrossProjectInternalTarget() {
        when(controlRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateFindingTarget(
                        projectId, FindingLinkTargetType.CONTROL, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not found");
    }

    @ParameterizedTest
    @EnumSource(
            value = AuditLinkTargetType.class,
            names = {"ASSET", "CONTROL", "RISK_SCENARIO", "EVIDENCE", "FINDING"})
    void validateAuditTargetAcceptsInternalTargets(AuditLinkTargetType targetType) {
        stubAuditInternalTarget(targetType, true);

        var validated = graphTargetResolverService.validateAuditTarget(projectId, targetType, targetId, null);

        assertThat(validated.internal()).isTrue();
        assertThat(validated.targetEntityId()).isEqualTo(targetId);
        assertThat(validated.targetIdentifier()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = AuditLinkTargetType.class,
            names = {"FRAMEWORK", "EXTERNAL"})
    void validateAuditTargetAcceptsExternalTargets(AuditLinkTargetType targetType) {
        var validated = graphTargetResolverService.validateAuditTarget(projectId, targetType, null, "ISO-27001");

        assertThat(validated.internal()).isFalse();
        assertThat(validated.targetEntityId()).isNull();
        assertThat(validated.targetIdentifier()).isEqualTo("ISO-27001");
    }

    @Test
    void validateAuditTargetRejectsRetiredTargetType() {
        assertThatThrownBy(() -> graphTargetResolverService.validateAuditTarget(
                        projectId, AuditLinkTargetType.RISK_REGISTER_RECORD, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("retired");
    }

    @Test
    void validateAuditTargetRejectsMissingInternalTargetEntityId() {
        assertThatThrownBy(() -> graphTargetResolverService.validateAuditTarget(
                        projectId, AuditLinkTargetType.CONTROL, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetEntityId");
    }

    @Test
    void validateAuditTargetRejectsMissingExternalIdentifier() {
        assertThatThrownBy(() -> graphTargetResolverService.validateAuditTarget(
                        projectId, AuditLinkTargetType.FRAMEWORK, null, " "))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetIdentifier");
    }

    @Test
    void validateAuditTargetRejectsCrossProjectInternalTarget() {
        when(assetRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateAuditTarget(
                        projectId, AuditLinkTargetType.ASSET, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void validateAuditTargetRejectsMissingFinding() {
        when(findingRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateAuditTarget(
                        projectId, AuditLinkTargetType.FINDING, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Finding");
    }

    @Test
    void validateAuditTargetRejectsMissingEvidenceArtifact() {
        when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> graphTargetResolverService.validateAuditTarget(
                        projectId, AuditLinkTargetType.EVIDENCE, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Evidence");
    }

    // GC-L006 cycle 2: EVIDENCE is a first-class aggregate (ADR-045) and every
    // link-target validator must resolve it internally against
    // EvidenceArtifactRepository, mirroring the audit validator. These tests pin
    // that invariant across all five non-audit validators so a future caller
    // cannot regress one back to externalTarget without a failing test.

    @Test
    void validateAssetTargetRejectsMissingEvidenceArtifact() {
        when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> graphTargetResolverService.validateAssetTarget(
                        projectId, AssetLinkTargetType.EVIDENCE, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Evidence");
    }

    @Test
    void validateRiskScenarioTargetRejectsMissingEvidenceArtifact() {
        when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> graphTargetResolverService.validateRiskScenarioTarget(
                        projectId, RiskScenarioLinkTargetType.EVIDENCE, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Evidence");
    }

    @Test
    void validateThreatModelTargetRejectsMissingEvidenceArtifact() {
        when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> graphTargetResolverService.validateThreatModelTarget(
                        projectId, ThreatModelLinkTargetType.EVIDENCE, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Evidence");
    }

    @Test
    void validateFindingTargetRejectsMissingEvidenceArtifact() {
        when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> graphTargetResolverService.validateFindingTarget(
                        projectId, FindingLinkTargetType.EVIDENCE, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Evidence");
    }

    // GC-G007: Document is a first-class graph participant. validateDocumentTarget
    // enforces project-scoped existence for every document link reference.

    @Test
    void validateDocumentTargetHappyPathReturnsInternalTarget() {
        when(documentRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(true);

        var validated = graphTargetResolverService.validateDocumentTarget(projectId, targetId);

        assertThat(validated.internal()).isTrue();
        assertThat(validated.targetEntityId()).isEqualTo(targetId);
        assertThat(validated.targetIdentifier()).isNull();
    }

    @Test
    void validateDocumentTargetRejectsNullTargetEntityId() {
        assertThatThrownBy(() -> graphTargetResolverService.validateDocumentTarget(projectId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Document links require targetEntityId");
    }

    @Test
    void validateDocumentTargetRejectsDocumentNotInProject() {
        when(documentRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateDocumentTarget(projectId, targetId))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Document target not found in the requested project");
    }
}
