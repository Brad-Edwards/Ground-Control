package com.keplerops.groundcontrol.domain.controls.service;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult.WorkspaceAssessment;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult.WorkspaceControl;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult.WorkspaceControlTest;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult.WorkspaceEvidence;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult.WorkspaceFinding;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult.WorkspaceRiskMapping;
import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceResult.WorkspaceScopedImplementation;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.controls.state.ControlTestConclusion;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.model.FindingLink;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.riskcontrol.model.MappingEvidenceRef;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ControlWorkspaceService {

    private static final int PREVIEW_LIMIT = 120;

    private final ControlRepository controlRepository;
    private final ScopedControlImplementationRepository scopedControlImplementationRepository;
    private final ControlTestRepository controlTestRepository;
    private final ControlEffectivenessAssessmentRepository assessmentRepository;
    private final EvidenceArtifactRepository evidenceArtifactRepository;
    private final FindingLinkRepository findingLinkRepository;
    private final RiskControlMappingRepository riskControlMappingRepository;

    public ControlWorkspaceService(
            ControlRepository controlRepository,
            ScopedControlImplementationRepository scopedControlImplementationRepository,
            ControlTestRepository controlTestRepository,
            ControlEffectivenessAssessmentRepository assessmentRepository,
            EvidenceArtifactRepository evidenceArtifactRepository,
            FindingLinkRepository findingLinkRepository,
            RiskControlMappingRepository riskControlMappingRepository) {
        this.controlRepository = controlRepository;
        this.scopedControlImplementationRepository = scopedControlImplementationRepository;
        this.controlTestRepository = controlTestRepository;
        this.assessmentRepository = assessmentRepository;
        this.evidenceArtifactRepository = evidenceArtifactRepository;
        this.findingLinkRepository = findingLinkRepository;
        this.riskControlMappingRepository = riskControlMappingRepository;
    }

    public ControlWorkspaceResult workspace(
            UUID projectId,
            Instant asOf,
            int freshnessWindowDays,
            ControlStatus status,
            ControlFunction controlFunction,
            String owner,
            String queue) {
        if (freshnessWindowDays <= 0) {
            throw new DomainValidationException(
                    "freshnessWindowDays must be positive",
                    "validation_error",
                    Map.of("parameter", "freshnessWindowDays", "value", freshnessWindowDays));
        }

        var controls = controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        var context = loadContext(projectId, asOf);
        var ownerFilter = normalize(owner);
        var queueFilter = normalizeQueue(queue);

        var items = controls.stream()
                .map(control -> composeControl(control, context))
                .filter(item -> status == null || item.status() == status)
                .filter(item -> controlFunction == null || item.controlFunction() == controlFunction)
                .filter(item -> ownerFilter == null || containsIgnoreCase(item.owner(), ownerFilter))
                .filter(item -> queueFilter == null || item.queueReasons().contains(queueFilter))
                .toList();
        return new ControlWorkspaceResult(items);
    }

    private WorkspaceContext loadContext(UUID projectId, Instant asOf) {
        var asOfDate = asOf == null ? null : asOf.atZone(ZoneOffset.UTC).toLocalDate();
        var scopedImplementations =
                scopedControlImplementationRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        var tests = asOfDate == null
                ? controlTestRepository.findByProjectIdOrderByTestDateDesc(projectId)
                : controlTestRepository.findByProjectIdAndTestDateLessThanEqualOrderByTestDateDesc(projectId, asOfDate);
        var assessments = asOfDate == null
                ? assessmentRepository.findByProjectIdOrderByAssessedAtDesc(projectId)
                : assessmentRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        projectId, asOfDate);
        var evidence = asOf == null
                ? evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(projectId)
                : evidenceArtifactRepository.findByProjectIdAndDerivedAtLessThanEqualOrderByDerivedAtDesc(
                        projectId, asOf);
        var findingLinks = findingLinkRepository.findByProjectId(projectId);
        var mappings = riskControlMappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);

        var testsById = new LinkedHashMap<UUID, ControlTest>();
        var testsByControl = new LinkedHashMap<UUID, List<ControlTest>>();
        for (var test : tests) {
            testsById.put(test.getId(), test);
            addByControl(testsByControl, test.getControl(), test);
        }

        var assessmentsById = new LinkedHashMap<UUID, ControlEffectivenessAssessment>();
        var assessmentsByControl = new LinkedHashMap<UUID, List<ControlEffectivenessAssessment>>();
        for (var assessment : assessments) {
            assessmentsById.put(assessment.getId(), assessment);
            addByControl(assessmentsByControl, assessment.getControl(), assessment);
        }

        return new WorkspaceContext(
                groupScopedImplementations(scopedImplementations),
                testsById,
                testsByControl,
                assessmentsById,
                assessmentsByControl,
                groupEvidenceByControl(evidence, testsById, assessmentsById),
                groupFindingsByControl(findingLinks),
                groupMappingsByControl(mappings));
    }

    private WorkspaceControl composeControl(Control control, WorkspaceContext context) {
        var controlId = control.getId();
        var scopedImplementations = context.scopedImplementationsByControl().getOrDefault(controlId, List.of()).stream()
                .map(this::toScopedImplementation)
                .toList();
        var tests = context.testsByControl().getOrDefault(controlId, List.of());
        var assessments = context.assessmentsByControl().getOrDefault(controlId, List.of());
        var evidence = context.evidenceByControl().getOrDefault(controlId, List.of());
        var findings = context.findingsByControl().getOrDefault(controlId, List.of());
        var mappings = context.mappingsByControl().getOrDefault(controlId, List.of());
        var queueReasons = queueReasons(control, tests, assessments, findings);

        return new WorkspaceControl(
                controlId,
                control.getUid(),
                control.getTitle(),
                preview(control.getDescription()),
                preview(control.getObjective()),
                control.getControlFunction(),
                control.getStatus(),
                control.getOwner(),
                preview(control.getImplementationScope()),
                control.getCategory(),
                control.getSource(),
                scopedImplementations,
                tests.stream().map(this::toControlTest).toList(),
                assessments.stream().map(this::toAssessment).toList(),
                evidence.stream().map(this::toEvidence).toList(),
                findings.stream().map(this::toFinding).toList(),
                mappings.stream().map(this::toRiskMapping).toList(),
                queueReasons);
    }

    private List<String> queueReasons(
            Control control,
            List<ControlTest> tests,
            List<ControlEffectivenessAssessment> assessments,
            List<Finding> findings) {
        var reasons = new ArrayList<String>();
        if (isBlank(control.getOwner())) {
            reasons.add("OWNER_MISSING");
        }
        if (control.getStatus() == ControlStatus.DRAFT || control.getStatus() == ControlStatus.PROPOSED) {
            reasons.add("STATUS_DRAFT");
        }
        if (tests.isEmpty()) {
            reasons.add("TEST_EVIDENCE_MISSING");
        }
        if (assessments.isEmpty()) {
            reasons.add("ASSESSMENT_MISSING");
        }
        if (findings.stream().anyMatch(this::isOpenFinding)) {
            reasons.add("OPEN_EXCEPTION");
        }
        if (hasWeakEffectiveness(tests, assessments)) {
            reasons.add("EFFECTIVENESS_WEAK");
        }
        if (reasons.isEmpty()) {
            reasons.add("CURRENT");
        }
        return List.copyOf(reasons);
    }

    private boolean hasWeakEffectiveness(List<ControlTest> tests, List<ControlEffectivenessAssessment> assessments) {
        if (tests.stream().anyMatch(test -> test.getConclusion() == ControlTestConclusion.INEFFECTIVE)) {
            return true;
        }
        if (assessments.isEmpty()) {
            return false;
        }
        var latest = assessments.getFirst();
        return latest.getDesignEffectiveness() != ControlEffectivenessRating.EFFECTIVE
                || latest.getOperatingEffectiveness() != ControlEffectivenessRating.EFFECTIVE;
    }

    private boolean isOpenFinding(Finding finding) {
        return finding.getStatus() != FindingStatus.REMEDIATION_COMPLETE
                && finding.getStatus() != FindingStatus.VERIFIED_CLOSED;
    }

    private WorkspaceScopedImplementation toScopedImplementation(ScopedControlImplementation scoped) {
        var asset = scoped.getOperationalAsset();
        return new WorkspaceScopedImplementation(
                scoped.getId(),
                scoped.getUid(),
                scoped.getName(),
                preview(scoped.getImplementationScope()),
                asset == null ? null : asset.getId(),
                asset == null ? null : asset.getUid(),
                asset == null ? null : asset.getName());
    }

    private WorkspaceControlTest toControlTest(ControlTest test) {
        return new WorkspaceControlTest(
                test.getId(),
                test.getUid(),
                nameOf(test.getMethodology()),
                test.getConclusion(),
                test.getTesterIdentity(),
                test.getTestDate(),
                preview(test.getNotes()));
    }

    private WorkspaceAssessment toAssessment(ControlEffectivenessAssessment assessment) {
        return new WorkspaceAssessment(
                assessment.getId(),
                assessment.getUid(),
                nameOf(assessment.getDesignEffectiveness()),
                nameOf(assessment.getOperatingEffectiveness()),
                assessment.getAssessedAt(),
                assessment.getAssessor(),
                assessment.getSupportingTestIds() == null ? List.of() : List.copyOf(assessment.getSupportingTestIds()));
    }

    private WorkspaceEvidence toEvidence(EvidenceArtifact artifact) {
        return new WorkspaceEvidence(
                artifact.getId(),
                artifact.getUid(),
                artifact.getTitle(),
                preview(artifact.getSummary()),
                nameOf(artifact.getEvidenceType()),
                artifact.getDerivedAt());
    }

    private WorkspaceFinding toFinding(Finding finding) {
        return new WorkspaceFinding(
                finding.getId(),
                finding.getUid(),
                finding.getTitle(),
                nameOf(finding.getFindingType()),
                nameOf(finding.getSeverity()),
                nameOf(finding.getStatus()),
                finding.getOwner(),
                finding.getDueDate());
    }

    private WorkspaceRiskMapping toRiskMapping(RiskControlMapping mapping) {
        var scenario = mapping.getRiskScenario();
        var record = mapping.getRiskRegisterRecord();
        return new WorkspaceRiskMapping(
                mapping.getId(),
                nameOf(mapping.getControlRole()),
                scenario != null ? scenario.getUid() : record == null ? null : record.getUid(),
                scenario != null ? scenario.getTitle() : record == null ? null : record.getTitle(),
                preview(mapping.getMappingObjective()),
                mapping.getEvidenceRefs() == null
                        ? List.of()
                        : mapping.getEvidenceRefs().stream()
                                .map(this::toMappingEvidenceRef)
                                .toList());
    }

    private ControlWorkspaceResult.WorkspaceMappingEvidenceRef toMappingEvidenceRef(MappingEvidenceRef ref) {
        return new ControlWorkspaceResult.WorkspaceMappingEvidenceRef(
                ref.getEvidenceRef(), preview(ref.getEvidenceNote()), ref.getEvidenceArtifactId());
    }

    private Map<UUID, List<ScopedControlImplementation>> groupScopedImplementations(
            List<ScopedControlImplementation> scopedImplementations) {
        var grouped = new LinkedHashMap<UUID, List<ScopedControlImplementation>>();
        for (var scoped : scopedImplementations) {
            addByControl(grouped, scoped.getControl(), scoped);
        }
        return grouped;
    }

    private Map<UUID, List<EvidenceArtifact>> groupEvidenceByControl(
            List<EvidenceArtifact> evidence,
            Map<UUID, ControlTest> testsById,
            Map<UUID, ControlEffectivenessAssessment> assessmentsById) {
        var grouped = new LinkedHashMap<UUID, LinkedHashMap<UUID, EvidenceArtifact>>();
        for (var artifact : evidence) {
            var sources = artifact.getSources() == null ? List.<EvidenceSourceRef>of() : artifact.getSources();
            for (var source : sources) {
                var controlId =
                        controlIdForSource(source.sourceKind(), source.sourceEntityId(), testsById, assessmentsById);
                if (controlId != null) {
                    grouped.computeIfAbsent(controlId, ignored -> new LinkedHashMap<>())
                            .putIfAbsent(artifact.getId(), artifact);
                }
            }
        }
        var result = new LinkedHashMap<UUID, List<EvidenceArtifact>>();
        grouped.forEach((controlId, artifacts) -> result.put(controlId, List.copyOf(artifacts.values())));
        return result;
    }

    private UUID controlIdForSource(
            EvidenceSourceKind kind,
            UUID sourceEntityId,
            Map<UUID, ControlTest> testsById,
            Map<UUID, ControlEffectivenessAssessment> assessmentsById) {
        if (kind == null || sourceEntityId == null) {
            return null;
        }
        if (kind == EvidenceSourceKind.CONTROL_TEST) {
            var test = testsById.get(sourceEntityId);
            return test == null || test.getControl() == null
                    ? null
                    : test.getControl().getId();
        }
        if (kind == EvidenceSourceKind.CONTROL_EFFECTIVENESS_ASSESSMENT) {
            var assessment = assessmentsById.get(sourceEntityId);
            return assessment == null || assessment.getControl() == null
                    ? null
                    : assessment.getControl().getId();
        }
        return null;
    }

    private Map<UUID, List<Finding>> groupFindingsByControl(List<FindingLink> findingLinks) {
        var grouped = new LinkedHashMap<UUID, LinkedHashMap<UUID, Finding>>();
        for (var link : findingLinks) {
            if (link.getTargetType() != FindingLinkTargetType.CONTROL || link.getTargetEntityId() == null) {
                continue;
            }
            var finding = link.getFinding();
            grouped.computeIfAbsent(link.getTargetEntityId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(finding.getId(), finding);
        }
        var result = new LinkedHashMap<UUID, List<Finding>>();
        grouped.forEach((controlId, findings) -> result.put(controlId, List.copyOf(findings.values())));
        return result;
    }

    private Map<UUID, List<RiskControlMapping>> groupMappingsByControl(List<RiskControlMapping> mappings) {
        var grouped = new LinkedHashMap<UUID, LinkedHashMap<UUID, RiskControlMapping>>();
        for (var mapping : mappings) {
            var controlId = controlIdForMapping(mapping);
            if (controlId == null) {
                continue;
            }
            grouped.computeIfAbsent(controlId, ignored -> new LinkedHashMap<>()).putIfAbsent(mapping.getId(), mapping);
        }
        var result = new LinkedHashMap<UUID, List<RiskControlMapping>>();
        grouped.forEach((controlId, rows) -> result.put(controlId, List.copyOf(rows.values())));
        return result;
    }

    private UUID controlIdForMapping(RiskControlMapping mapping) {
        if (mapping.getControl() != null) {
            return mapping.getControl().getId();
        }
        var scoped = mapping.getScopedImplementation();
        return scoped == null || scoped.getControl() == null
                ? null
                : scoped.getControl().getId();
    }

    private static <T> void addByControl(Map<UUID, List<T>> grouped, Control control, T item) {
        if (control == null || control.getId() == null) {
            return;
        }
        grouped.computeIfAbsent(control.getId(), ignored -> new ArrayList<>()).add(item);
    }

    private static String preview(String value) {
        if (value == null) {
            return null;
        }
        var compact = value.trim();
        if (compact.length() <= PREVIEW_LIMIT) {
            return compact;
        }
        return compact.substring(0, PREVIEW_LIMIT - 3) + "...";
    }

    private static String normalize(String value) {
        return isBlank(value) ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeQueue(String value) {
        return isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private record WorkspaceContext(
            Map<UUID, List<ScopedControlImplementation>> scopedImplementationsByControl,
            Map<UUID, ControlTest> testsById,
            Map<UUID, List<ControlTest>> testsByControl,
            Map<UUID, ControlEffectivenessAssessment> assessmentsById,
            Map<UUID, List<ControlEffectivenessAssessment>> assessmentsByControl,
            Map<UUID, List<EvidenceArtifact>> evidenceByControl,
            Map<UUID, List<Finding>> findingsByControl,
            Map<UUID, List<RiskControlMapping>> mappingsByControl) {}
}
