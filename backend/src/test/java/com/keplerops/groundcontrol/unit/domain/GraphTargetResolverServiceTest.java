package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.audits.repository.AuditRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
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
class GraphTargetResolverServiceTest {
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

    private void stubAssetInternalTarget(AssetLinkTargetType targetType, boolean exists) {
        switch (targetType) {
            case REQUIREMENT -> when(requirementRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case RISK_SCENARIO -> when(riskScenarioRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case CONTROL -> when(controlRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case THREAT_MODEL_ENTRY -> when(threatModelRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case FINDING -> when(findingRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case AUDIT -> when(auditRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case EVIDENCE -> when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(mock(
                                            com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact.class))
                                    : java.util.Optional.empty());
            case RISK_REGISTER_RECORD,
                    RISK_ASSESSMENT_RESULT,
                    TREATMENT_PLAN,
                    METHODOLOGY_PROFILE,
                    ISSUE,
                    CODE,
                    CONFIGURATION,
                    EXTERNAL -> throw new IllegalArgumentException("Not an internal target type");
        }
    }

    private void stubScenarioInternalTarget(RiskScenarioLinkTargetType targetType, boolean exists) {
        switch (targetType) {
            case OBSERVATION -> when(observationRepository.findByIdWithAssetAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(
                                            mock(com.keplerops.groundcontrol.domain.assets.model.Observation.class))
                                    : java.util.Optional.empty());
            case ASSET -> when(assetRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case REQUIREMENT -> when(requirementRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case CONTROL -> when(controlRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case THREAT_MODEL -> when(threatModelRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case FINDING -> when(findingRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case AUDIT_RECORD -> when(auditRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case EVIDENCE -> when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(mock(
                                            com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact.class))
                                    : java.util.Optional.empty());
            case RISK_REGISTER_RECORD,
                    RISK_ASSESSMENT_RESULT,
                    TREATMENT_PLAN,
                    METHODOLOGY_PROFILE,
                    VULNERABILITY,
                    EXTERNAL -> throw new IllegalArgumentException("Not an internal target type");
        }
    }

    private void stubThreatModelInternalTarget(ThreatModelLinkTargetType targetType, boolean exists) {
        switch (targetType) {
            case ASSET -> when(assetRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case REQUIREMENT -> when(requirementRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case CONTROL -> when(controlRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case RISK_SCENARIO -> when(riskScenarioRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case OBSERVATION -> when(observationRepository.findByIdWithAssetAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(
                                            mock(com.keplerops.groundcontrol.domain.assets.model.Observation.class))
                                    : java.util.Optional.empty());
            case VERIFICATION_RESULT -> when(verificationResultRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case FINDING -> when(findingRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case EVIDENCE -> when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(mock(
                                            com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact.class))
                                    : java.util.Optional.empty());
            case RISK_ASSESSMENT_RESULT,
                    ARCHITECTURE_MODEL,
                    CODE,
                    ISSUE,
                    EXTERNAL -> throw new IllegalArgumentException("Not an internal target type");
        }
    }

    private void stubControlInternalTarget(ControlLinkTargetType targetType, boolean exists) {
        switch (targetType) {
            case ASSET -> when(assetRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case REQUIREMENT -> when(requirementRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case RISK_SCENARIO -> when(riskScenarioRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case OBSERVATION -> when(observationRepository.findByIdWithAssetAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(
                                            mock(com.keplerops.groundcontrol.domain.assets.model.Observation.class))
                                    : java.util.Optional.empty());
            case FINDING -> when(findingRepository.existsByIdAndProjectId(targetId, projectId))
                    .thenReturn(exists);
            case EVIDENCE -> when(evidenceArtifactRepository.findByIdAndProjectId(targetId, projectId))
                    .thenReturn(
                            exists
                                    ? java.util.Optional.of(mock(
                                            com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact.class))
                                    : java.util.Optional.empty());
            case RISK_REGISTER_RECORD,
                    RISK_ASSESSMENT_RESULT,
                    TREATMENT_PLAN,
                    METHODOLOGY_PROFILE,
                    CODE,
                    CONFIGURATION,
                    OPERATIONAL_ARTIFACT,
                    EXTERNAL -> throw new IllegalArgumentException("Not an internal target type");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AssetLinkTargetType.class,
            names = {"REQUIREMENT", "RISK_SCENARIO", "CONTROL", "THREAT_MODEL_ENTRY", "FINDING", "AUDIT", "EVIDENCE"})
    void validateAssetTargetAcceptsInternalTargets(AssetLinkTargetType targetType) {
        stubAssetInternalTarget(targetType, true);

        var validated = graphTargetResolverService.validateAssetTarget(projectId, targetType, targetId, null);

        assertThat(validated.internal()).isTrue();
        assertThat(validated.targetEntityId()).isEqualTo(targetId);
        assertThat(validated.targetIdentifier()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = AssetLinkTargetType.class,
            names = {"ISSUE", "CODE", "CONFIGURATION", "EXTERNAL"})
    void validateAssetTargetAcceptsExternalTargets(AssetLinkTargetType targetType) {
        var validated = graphTargetResolverService.validateAssetTarget(projectId, targetType, null, "EXT-1");

        assertThat(validated.internal()).isFalse();
        assertThat(validated.targetEntityId()).isNull();
        assertThat(validated.targetIdentifier()).isEqualTo("EXT-1");
    }

    // ADR-089: RISK_REGISTER_RECORD, RISK_ASSESSMENT_RESULT, TREATMENT_PLAN, and
    // METHODOLOGY_PROFILE are retired target types. The enum constants remain (so
    // historical rows referencing them stay deserializable) but no new link may
    // resolve against them.
    @ParameterizedTest
    @EnumSource(
            value = AssetLinkTargetType.class,
            names = {"RISK_REGISTER_RECORD", "RISK_ASSESSMENT_RESULT", "TREATMENT_PLAN", "METHODOLOGY_PROFILE"})
    void validateAssetTargetRejectsRetiredTargetTypes(AssetLinkTargetType targetType) {
        assertThatThrownBy(() -> graphTargetResolverService.validateAssetTarget(projectId, targetType, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("retired");
    }

    @ParameterizedTest
    @EnumSource(
            value = RiskScenarioLinkTargetType.class,
            names = {
                "OBSERVATION",
                "ASSET",
                "REQUIREMENT",
                "CONTROL",
                "THREAT_MODEL",
                "FINDING",
                "AUDIT_RECORD",
                "EVIDENCE"
            })
    void validateRiskScenarioTargetAcceptsInternalTargets(RiskScenarioLinkTargetType targetType) {
        stubScenarioInternalTarget(targetType, true);

        var validated = graphTargetResolverService.validateRiskScenarioTarget(projectId, targetType, targetId, null);

        assertThat(validated.internal()).isTrue();
        assertThat(validated.targetEntityId()).isEqualTo(targetId);
    }

    @ParameterizedTest
    @EnumSource(
            value = RiskScenarioLinkTargetType.class,
            names = {"VULNERABILITY", "EXTERNAL"})
    void validateRiskScenarioTargetAcceptsExternalTargets(RiskScenarioLinkTargetType targetType) {
        var validated = graphTargetResolverService.validateRiskScenarioTarget(projectId, targetType, null, "EXT-2");

        assertThat(validated.internal()).isFalse();
        assertThat(validated.targetIdentifier()).isEqualTo("EXT-2");
    }

    @ParameterizedTest
    @EnumSource(
            value = RiskScenarioLinkTargetType.class,
            names = {"RISK_REGISTER_RECORD", "RISK_ASSESSMENT_RESULT", "TREATMENT_PLAN", "METHODOLOGY_PROFILE"})
    void validateRiskScenarioTargetRejectsRetiredTargetTypes(RiskScenarioLinkTargetType targetType) {
        assertThatThrownBy(() ->
                        graphTargetResolverService.validateRiskScenarioTarget(projectId, targetType, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("retired");
    }

    @ParameterizedTest
    @EnumSource(
            value = ThreatModelLinkTargetType.class,
            names = {
                "ASSET",
                "REQUIREMENT",
                "CONTROL",
                "RISK_SCENARIO",
                "OBSERVATION",
                "VERIFICATION_RESULT",
                "FINDING",
                "EVIDENCE"
            })
    void validateThreatModelTargetAcceptsInternalTargets(ThreatModelLinkTargetType targetType) {
        stubThreatModelInternalTarget(targetType, true);

        var validated = graphTargetResolverService.validateThreatModelTarget(projectId, targetType, targetId, null);

        assertThat(validated.internal()).isTrue();
        assertThat(validated.targetEntityId()).isEqualTo(targetId);
    }

    @ParameterizedTest
    @EnumSource(
            value = ThreatModelLinkTargetType.class,
            names = {"CODE", "ISSUE", "EXTERNAL"})
    void validateThreatModelTargetAcceptsExternalTargets(ThreatModelLinkTargetType targetType) {
        var validated =
                graphTargetResolverService.validateThreatModelTarget(projectId, targetType, null, "backend/Auth.java");

        assertThat(validated.internal()).isFalse();
        assertThat(validated.targetEntityId()).isNull();
        assertThat(validated.targetIdentifier()).isEqualTo("backend/Auth.java");
    }

    @ParameterizedTest
    @EnumSource(
            value = ThreatModelLinkTargetType.class,
            names = {"RISK_ASSESSMENT_RESULT", "ARCHITECTURE_MODEL"})
    void validateThreatModelTargetRejectsRetiredTargetTypes(ThreatModelLinkTargetType targetType) {
        assertThatThrownBy(() ->
                        graphTargetResolverService.validateThreatModelTarget(projectId, targetType, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("retired");
    }

    @Test
    void validateAssetTargetRejectsMissingInternalTargetEntityId() {
        assertThatThrownBy(() -> graphTargetResolverService.validateAssetTarget(
                        projectId, AssetLinkTargetType.REQUIREMENT, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetEntityId");
    }

    @Test
    void validateRiskScenarioTargetRejectsMissingExternalIdentifier() {
        assertThatThrownBy(() -> graphTargetResolverService.validateRiskScenarioTarget(
                        projectId, RiskScenarioLinkTargetType.EXTERNAL, null, " "))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetIdentifier");
    }

    @Test
    void validateAssetTargetRejectsMissingProjectScopedTarget() {
        when(requirementRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateAssetTarget(
                        projectId, AssetLinkTargetType.REQUIREMENT, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void validateRiskScenarioTargetRejectsMissingProjectScopedTarget() {
        when(assetRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateRiskScenarioTarget(
                        projectId, RiskScenarioLinkTargetType.ASSET, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void validateAssetTargetRejectsMissingThreatModelEntry() {
        when(threatModelRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateAssetTarget(
                        projectId, AssetLinkTargetType.THREAT_MODEL_ENTRY, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Threat model");
    }

    @Test
    void validateRiskScenarioTargetRejectsMissingThreatModel() {
        when(threatModelRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateRiskScenarioTarget(
                        projectId, RiskScenarioLinkTargetType.THREAT_MODEL, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Threat model");
    }

    @Test
    void validateThreatModelTargetRejectsMissingInternalTargetEntityId() {
        assertThatThrownBy(() -> graphTargetResolverService.validateThreatModelTarget(
                        projectId, ThreatModelLinkTargetType.ASSET, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetEntityId");
    }

    @Test
    void validateThreatModelTargetRejectsMissingProjectScopedTarget() {
        when(assetRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateThreatModelTarget(
                        projectId, ThreatModelLinkTargetType.ASSET, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void validateThreatModelTargetRejectsMissingExternalIdentifier() {
        assertThatThrownBy(() -> graphTargetResolverService.validateThreatModelTarget(
                        projectId, ThreatModelLinkTargetType.EXTERNAL, null, " "))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("targetIdentifier");
    }

    @Test
    void validateThreatModelTargetRejectsMissingFinding() {
        when(findingRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateThreatModelTarget(
                        projectId, ThreatModelLinkTargetType.FINDING, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Finding");
    }

    @Test
    void validateAssetTargetRejectsMissingFinding() {
        when(findingRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateAssetTarget(
                        projectId, AssetLinkTargetType.FINDING, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Finding");
    }

    // PR #875 security provenance: an attacker passing a UUID from project B into
    // project A's ControlLink used to silently persist a cross-project edge. The
    // resolver now enforces project-scoped existence for every internal type, so a
    // non-existent UUID and a cross-project UUID are observationally identical here —
    // both produce DomainValidationException("not found in the requested project").
    // This single parameterized test subsumes the old per-type singletons.
    @ParameterizedTest
    @EnumSource(
            value = ControlLinkTargetType.class,
            names = {"ASSET", "REQUIREMENT", "RISK_SCENARIO", "OBSERVATION", "FINDING", "EVIDENCE"})
    void validateControlTargetRejectsInternalTargets(ControlLinkTargetType targetType) {
        stubControlInternalTarget(targetType, false);

        assertThatThrownBy(
                        () -> graphTargetResolverService.validateControlTarget(projectId, targetType, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("not found in the requested project");
    }

    @Test
    void validateRiskScenarioTargetRejectsMissingFinding() {
        when(findingRepository.existsByIdAndProjectId(targetId, projectId)).thenReturn(false);

        assertThatThrownBy(() -> graphTargetResolverService.validateRiskScenarioTarget(
                        projectId, RiskScenarioLinkTargetType.FINDING, targetId, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Finding");
    }

    @ParameterizedTest
    @EnumSource(
            value = ControlLinkTargetType.class,
            names = {"ASSET", "REQUIREMENT", "RISK_SCENARIO", "OBSERVATION", "FINDING", "EVIDENCE"})
    void validateControlTargetAcceptsInternalTargets(ControlLinkTargetType targetType) {
        stubControlInternalTarget(targetType, true);

        var validated = graphTargetResolverService.validateControlTarget(projectId, targetType, targetId, null);

        assertThat(validated.internal()).isTrue();
        assertThat(validated.targetEntityId()).isEqualTo(targetId);
    }

    @ParameterizedTest
    @EnumSource(
            value = ControlLinkTargetType.class,
            names = {"CODE", "CONFIGURATION", "OPERATIONAL_ARTIFACT", "EXTERNAL"})
    void validateControlTargetAcceptsExternalTargets(ControlLinkTargetType targetType) {
        var validated = graphTargetResolverService.validateControlTarget(projectId, targetType, null, "ref://ext/1");

        assertThat(validated.internal()).isFalse();
        assertThat(validated.targetEntityId()).isNull();
        assertThat(validated.targetIdentifier()).isEqualTo("ref://ext/1");
    }
}
