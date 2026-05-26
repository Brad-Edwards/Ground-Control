package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.audits.repository.AuditRepository;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.verification.repository.VerificationResultRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GraphTargetResolverService {

    public record ValidatedTarget(UUID targetEntityId, String targetIdentifier, boolean internal) {}

    // Human-readable labels used by internalTarget to format DomainValidationException
    // messages. Each label is reused across multiple validate*Target methods; defining
    // them here keeps the wire-shape of validation errors stable and avoids the literal
    // duplication that triggers Sonar S1192.
    private static final String LABEL_REQUIREMENT = "Requirement";
    private static final String LABEL_ASSET = "Asset";
    private static final String LABEL_OBSERVATION = "Observation";
    private static final String LABEL_RISK_SCENARIO = "Risk scenario";
    private static final String LABEL_RISK_REGISTER_RECORD = "Risk register record";
    private static final String LABEL_RISK_ASSESSMENT_RESULT = "Risk assessment result";
    private static final String LABEL_TREATMENT_PLAN = "Treatment plan";
    private static final String LABEL_METHODOLOGY_PROFILE = "Methodology profile";
    private static final String LABEL_CONTROL = "Control";
    private static final String LABEL_THREAT_MODEL = "Threat model";
    private static final String LABEL_VERIFICATION_RESULT = "Verification result";
    private static final String LABEL_FINDING = "Finding";
    private static final String LABEL_AUDIT = "Audit";
    private static final String LABEL_EVIDENCE = "Evidence";

    private final RequirementRepository requirementRepository;
    private final OperationalAssetRepository assetRepository;
    private final ObservationRepository observationRepository;
    private final RiskScenarioRepository riskScenarioRepository;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;
    private final ControlRepository controlRepository;
    private final ThreatModelRepository threatModelRepository;
    private final VerificationResultRepository verificationResultRepository;
    private final FindingRepository findingRepository;
    private final AuditRepository auditRepository;
    private final EvidenceArtifactRepository evidenceArtifactRepository;

    public GraphTargetResolverService(
            RequirementRepository requirementRepository,
            OperationalAssetRepository assetRepository,
            ObservationRepository observationRepository,
            RiskScenarioRepository riskScenarioRepository,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            TreatmentPlanRepository treatmentPlanRepository,
            MethodologyProfileRepository methodologyProfileRepository,
            ControlRepository controlRepository,
            ThreatModelRepository threatModelRepository,
            VerificationResultRepository verificationResultRepository,
            FindingRepository findingRepository,
            AuditRepository auditRepository,
            EvidenceArtifactRepository evidenceArtifactRepository) {
        this.requirementRepository = requirementRepository;
        this.assetRepository = assetRepository;
        this.observationRepository = observationRepository;
        this.riskScenarioRepository = riskScenarioRepository;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
        this.controlRepository = controlRepository;
        this.threatModelRepository = threatModelRepository;
        this.verificationResultRepository = verificationResultRepository;
        this.findingRepository = findingRepository;
        this.auditRepository = auditRepository;
        this.evidenceArtifactRepository = evidenceArtifactRepository;
    }

    public ValidatedTarget validateAssetTarget(
            UUID projectId, AssetLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return switch (targetType) {
            case REQUIREMENT -> internalTarget(
                    targetEntityId,
                    requirementRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_REQUIREMENT);
            case RISK_SCENARIO -> internalTarget(
                    targetEntityId,
                    riskScenarioRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_RISK_SCENARIO);
            case RISK_REGISTER_RECORD -> internalTarget(
                    targetEntityId,
                    riskRegisterRecordRepository
                            .findByIdAndProjectIdWithScenarios(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_REGISTER_RECORD);
            case RISK_ASSESSMENT_RESULT -> internalTarget(
                    targetEntityId,
                    riskAssessmentResultRepository
                            .findByIdAndProjectIdWithObservations(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_ASSESSMENT_RESULT);
            case TREATMENT_PLAN -> internalTarget(
                    targetEntityId,
                    treatmentPlanRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_TREATMENT_PLAN);
            case METHODOLOGY_PROFILE -> internalTarget(
                    targetEntityId,
                    methodologyProfileRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_METHODOLOGY_PROFILE);
            case CONTROL -> internalTarget(
                    targetEntityId, controlRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_CONTROL);
            case THREAT_MODEL_ENTRY -> internalTarget(
                    targetEntityId,
                    threatModelRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_THREAT_MODEL);
            case FINDING -> internalTarget(
                    targetEntityId, findingRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_FINDING);
            case AUDIT -> internalTarget(
                    targetEntityId, auditRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_AUDIT);
            case EVIDENCE -> internalTarget(
                    targetEntityId,
                    evidenceArtifactRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_EVIDENCE);
            case ISSUE, CODE, CONFIGURATION, EXTERNAL -> externalTarget(targetIdentifier);
        };
    }

    public ValidatedTarget validateRiskScenarioTarget(
            UUID projectId, RiskScenarioLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return switch (targetType) {
            case OBSERVATION -> internalTarget(
                    targetEntityId,
                    observationRepository
                            .findByIdWithAssetAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_OBSERVATION);
            case ASSET -> internalTarget(
                    targetEntityId, assetRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_ASSET);
            case REQUIREMENT -> internalTarget(
                    targetEntityId,
                    requirementRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_REQUIREMENT);
            case RISK_REGISTER_RECORD -> internalTarget(
                    targetEntityId,
                    riskRegisterRecordRepository
                            .findByIdAndProjectIdWithScenarios(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_REGISTER_RECORD);
            case RISK_ASSESSMENT_RESULT -> internalTarget(
                    targetEntityId,
                    riskAssessmentResultRepository
                            .findByIdAndProjectIdWithObservations(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_ASSESSMENT_RESULT);
            case TREATMENT_PLAN -> internalTarget(
                    targetEntityId,
                    treatmentPlanRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_TREATMENT_PLAN);
            case METHODOLOGY_PROFILE -> internalTarget(
                    targetEntityId,
                    methodologyProfileRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_METHODOLOGY_PROFILE);
            case CONTROL -> internalTarget(
                    targetEntityId, controlRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_CONTROL);
            case THREAT_MODEL -> internalTarget(
                    targetEntityId,
                    threatModelRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_THREAT_MODEL);
            case FINDING -> internalTarget(
                    targetEntityId, findingRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_FINDING);
            case AUDIT_RECORD -> internalTarget(
                    targetEntityId, auditRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_AUDIT);
            case EVIDENCE -> internalTarget(
                    targetEntityId,
                    evidenceArtifactRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_EVIDENCE);
            case VULNERABILITY, EXTERNAL -> externalTarget(targetIdentifier);
        };
    }

    public ValidatedTarget validateControlTarget(
            UUID projectId, ControlLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return switch (targetType) {
            case ASSET -> internalTarget(
                    targetEntityId, assetRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_ASSET);
            case REQUIREMENT -> internalTarget(
                    targetEntityId,
                    requirementRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_REQUIREMENT);
            case RISK_SCENARIO -> internalTarget(
                    targetEntityId,
                    riskScenarioRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_RISK_SCENARIO);
            case RISK_REGISTER_RECORD -> internalTarget(
                    targetEntityId,
                    riskRegisterRecordRepository
                            .findByIdAndProjectIdWithScenarios(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_REGISTER_RECORD);
            case RISK_ASSESSMENT_RESULT -> internalTarget(
                    targetEntityId,
                    riskAssessmentResultRepository
                            .findByIdAndProjectIdWithObservations(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_ASSESSMENT_RESULT);
            case TREATMENT_PLAN -> internalTarget(
                    targetEntityId,
                    treatmentPlanRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_TREATMENT_PLAN);
            case METHODOLOGY_PROFILE -> internalTarget(
                    targetEntityId,
                    methodologyProfileRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_METHODOLOGY_PROFILE);
            case OBSERVATION -> internalTarget(
                    targetEntityId,
                    observationRepository
                            .findByIdWithAssetAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_OBSERVATION);
            case FINDING -> internalTarget(
                    targetEntityId, findingRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_FINDING);
            case EVIDENCE -> internalTarget(
                    targetEntityId,
                    evidenceArtifactRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_EVIDENCE);
            case CODE, CONFIGURATION, OPERATIONAL_ARTIFACT, EXTERNAL -> externalTarget(targetIdentifier);
        };
    }

    public ValidatedTarget validateThreatModelTarget(
            UUID projectId, ThreatModelLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return switch (targetType) {
            case ASSET -> internalTarget(
                    targetEntityId, assetRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_ASSET);
            case REQUIREMENT -> internalTarget(
                    targetEntityId,
                    requirementRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_REQUIREMENT);
            case CONTROL -> internalTarget(
                    targetEntityId, controlRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_CONTROL);
            case RISK_SCENARIO -> internalTarget(
                    targetEntityId,
                    riskScenarioRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_RISK_SCENARIO);
            case OBSERVATION -> internalTarget(
                    targetEntityId,
                    observationRepository
                            .findByIdWithAssetAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_OBSERVATION);
            case RISK_ASSESSMENT_RESULT -> internalTarget(
                    targetEntityId,
                    riskAssessmentResultRepository
                            .findByIdAndProjectIdWithObservations(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_ASSESSMENT_RESULT);
            case VERIFICATION_RESULT -> internalTarget(
                    targetEntityId,
                    verificationResultRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_VERIFICATION_RESULT);
            case FINDING -> internalTarget(
                    targetEntityId, findingRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_FINDING);
            case EVIDENCE -> internalTarget(
                    targetEntityId,
                    evidenceArtifactRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_EVIDENCE);
            case ARCHITECTURE_MODEL, CODE, ISSUE, EXTERNAL -> externalTarget(targetIdentifier);
        };
    }

    public ValidatedTarget validateFindingTarget(
            UUID projectId, FindingLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return switch (targetType) {
            case CONTROL -> internalTarget(
                    targetEntityId, controlRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_CONTROL);
            case RISK_SCENARIO -> internalTarget(
                    targetEntityId,
                    riskScenarioRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_RISK_SCENARIO);
            case ASSET -> internalTarget(
                    targetEntityId, assetRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_ASSET);
            case OBSERVATION -> internalTarget(
                    targetEntityId,
                    observationRepository
                            .findByIdWithAssetAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_OBSERVATION);
            case AUDIT -> internalTarget(
                    targetEntityId, auditRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_AUDIT);
            case EVIDENCE -> internalTarget(
                    targetEntityId,
                    evidenceArtifactRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_EVIDENCE);
            case OPERATIONAL_ARTIFACT, REMEDIATION_PLAN, EXTERNAL -> externalTarget(targetIdentifier);
        };
    }

    public ValidatedTarget validateAuditTarget(
            UUID projectId, AuditLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return switch (targetType) {
            case ASSET -> internalTarget(
                    targetEntityId, assetRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_ASSET);
            case CONTROL -> internalTarget(
                    targetEntityId, controlRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_CONTROL);
            case RISK_SCENARIO -> internalTarget(
                    targetEntityId,
                    riskScenarioRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_RISK_SCENARIO);
            case RISK_REGISTER_RECORD -> internalTarget(
                    targetEntityId,
                    riskRegisterRecordRepository
                            .findByIdAndProjectIdWithScenarios(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_REGISTER_RECORD);
            case EVIDENCE -> internalTarget(
                    targetEntityId,
                    evidenceArtifactRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_EVIDENCE);
            case FINDING -> internalTarget(
                    targetEntityId, findingRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_FINDING);
            case FRAMEWORK, EXTERNAL -> externalTarget(targetIdentifier);
        };
    }

    public ValidatedTarget validateReassessmentTriggerTarget(
            UUID projectId, ReassessmentTriggerTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return switch (targetType) {
            case ASSET -> internalTarget(
                    targetEntityId, assetRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_ASSET);
            case CONTROL -> internalTarget(
                    targetEntityId, controlRepository.existsByIdAndProjectId(targetEntityId, projectId), LABEL_CONTROL);
            case RISK_SCENARIO -> internalTarget(
                    targetEntityId,
                    riskScenarioRepository.existsByIdAndProjectId(targetEntityId, projectId),
                    LABEL_RISK_SCENARIO);
            case RISK_REGISTER_RECORD -> internalTarget(
                    targetEntityId,
                    riskRegisterRecordRepository
                            .findByIdAndProjectIdWithScenarios(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_REGISTER_RECORD);
            case RISK_ASSESSMENT_RESULT -> internalTarget(
                    targetEntityId,
                    riskAssessmentResultRepository
                            .findByIdAndProjectIdWithObservations(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_RISK_ASSESSMENT_RESULT);
            case TREATMENT_PLAN -> internalTarget(
                    targetEntityId,
                    treatmentPlanRepository
                            .findByIdAndProjectId(targetEntityId, projectId)
                            .isPresent(),
                    LABEL_TREATMENT_PLAN);
            case EXTERNAL -> externalTarget(targetIdentifier);
        };
    }

    private ValidatedTarget internalTarget(UUID targetEntityId, boolean exists, String label) {
        if (targetEntityId == null) {
            throw new DomainValidationException(label + " links require targetEntityId");
        }
        if (!exists) {
            throw new DomainValidationException(label + " target not found in the requested project");
        }
        return new ValidatedTarget(targetEntityId, null, true);
    }

    private ValidatedTarget externalTarget(String targetIdentifier) {
        if (targetIdentifier == null || targetIdentifier.isBlank()) {
            throw new DomainValidationException("External or unmodeled links require targetIdentifier");
        }
        return new ValidatedTarget(null, targetIdentifier, false);
    }
}
