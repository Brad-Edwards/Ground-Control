package com.keplerops.groundcontrol.domain.grcassessment.service;

import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationService;
import com.keplerops.groundcontrol.domain.derivation.service.BoundaryDeclaration;
import com.keplerops.groundcontrol.domain.derivation.service.CreateDerivationRunCommand;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationService;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcassessment.model.GrcAssessmentRun;
import com.keplerops.groundcontrol.domain.grcassessment.repository.GrcAssessmentRunRepository;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentMode;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewPolicy;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentRunState;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentScopeType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrcAssessmentRunService {

    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{7,64}$");
    private static final int DEFAULT_PARTITION_LIMIT = 100;
    private static final int MAX_PARTITION_LIMIT = 500;

    private final GrcAssessmentRunRepository runRepository;
    private final ProjectService projectService;
    private final DerivationService derivationService;
    private final ThreatEnumerationService threatEnumerationService;
    private final ControlIdentificationService controlIdentificationService;

    public GrcAssessmentRunService(
            GrcAssessmentRunRepository runRepository,
            ProjectService projectService,
            DerivationService derivationService,
            ThreatEnumerationService threatEnumerationService,
            ControlIdentificationService controlIdentificationService) {
        this.runRepository = runRepository;
        this.projectService = projectService;
        this.derivationService = derivationService;
        this.threatEnumerationService = threatEnumerationService;
        this.controlIdentificationService = controlIdentificationService;
    }

    @Transactional
    public GrcAssessmentRun createRun(CreateGrcAssessmentRunCommand command) {
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            var existing = runRepository.findByProjectIdAndIdempotencyKey(
                    command.projectId(), command.idempotencyKey().trim());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        var project = projectService.getById(command.projectId());
        var mode = require(command.mode(), "mode");
        var scopeType = require(command.scopeType(), "scopeType");
        var commitSha = normalizeCommit(command.commitSha(), mode);
        var baseCommitSha = normalizeOptionalCommit(command.baseCommitSha(), "baseCommitSha");
        var languages = normalizeTokens(command.languages(), "languages", mode != GrcAssessmentMode.RE_SCREEN);
        var surfaces = normalizeTokens(command.surfaces(), "surfaces", mode != GrcAssessmentMode.RE_SCREEN);
        var declaredBoundaries =
                command.declaredBoundaries() == null ? List.<BoundaryDeclaration>of() : command.declaredBoundaries();
        var partitions = buildPartitions(
                scopeType, command.scopeValues(), declaredBoundaries, partitionLimit(command.partitionLimit()));

        var reviewPolicy = command.reviewPolicy() == null ? GrcAssessmentReviewPolicy.REQUIRED : command.reviewPolicy();
        var reviewDecision = command.reviewDecision() == null
                ? GrcAssessmentReviewDecision.REQUEST_REVIEW
                : command.reviewDecision();
        var run = new GrcAssessmentRun(
                project,
                mode,
                scopeType,
                commitSha,
                baseCommitSha,
                languages,
                surfaces,
                reviewPolicy,
                blankToNull(command.idempotencyKey()));
        run.setScopeValues(normalizeScopeValues(command.scopeValues()));
        run.setDeclaredBoundaries(declaredBoundaries.stream()
                .map(GrcAssessmentRunService::boundaryToMap)
                .toList());
        run.setThreatPack(blankToNull(command.threatPackId()), blankToNull(command.threatPackVersion()));
        run.setReviewDecision(reviewDecision, null, null);
        run.recordPartitions(
                partitions.requestedCount(),
                partitions.uniquePartitions(),
                partitions.uniquePartitions().size());

        if (reviewDecision == GrcAssessmentReviewDecision.REJECTED) {
            run.setState(GrcAssessmentRunState.REJECTED);
            return runRepository.save(run);
        }
        if (reviewDecision == GrcAssessmentReviewDecision.APPROVED
                || reviewPolicy == GrcAssessmentReviewPolicy.DISABLED) {
            executeAndRecord(run, declaredBoundaries);
        }
        return runRepository.save(run);
    }

    @Transactional
    public GrcAssessmentRun reviewRun(ReviewGrcAssessmentRunCommand command) {
        var run = getRun(command.projectId(), command.runId());
        var decision = require(command.reviewDecision(), "reviewDecision");
        if (run.getState() != GrcAssessmentRunState.READY_FOR_REVIEW) {
            throw validation("Only READY_FOR_REVIEW GRC assessment runs can be reviewed", "state");
        }
        run.setReviewDecision(decision, command.reviewedBy(), command.reviewRationale());
        if (decision == GrcAssessmentReviewDecision.REJECTED) {
            run.setState(GrcAssessmentRunState.REJECTED);
            return runRepository.save(run);
        }
        if (decision == GrcAssessmentReviewDecision.APPROVED && run.getState() != GrcAssessmentRunState.COMMITTED) {
            executeAndRecord(run, mapsToBoundaries(run.getDeclaredBoundaries()));
        }
        return runRepository.save(run);
    }

    @Transactional(readOnly = true)
    public GrcAssessmentRun getRun(UUID projectId, UUID runId) {
        return runRepository
                .findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new NotFoundException("GRC assessment run not found: " + runId));
    }

    @Transactional(readOnly = true)
    public List<GrcAssessmentRun> listRuns(UUID projectId, int limit) {
        int bounded = Math.max(1, Math.min(limit <= 0 ? 25 : limit, 100));
        return runRepository.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(0, bounded));
    }

    private void executeAndRecord(GrcAssessmentRun run, List<BoundaryDeclaration> declaredBoundaries) {
        if (run.getMode() == GrcAssessmentMode.RE_SCREEN) {
            run.recordGraphEffects(reScreenEffects(run));
            return;
        }

        List<Map<String, Object>> effects = new ArrayList<>();
        for (var partition : run.getPartitions()) {
            var partitionKey = string(partition.get("partitionKey"));
            var paths = stringList(partition.get("paths"));
            DerivationScopeMode scopeMode =
                    "whole-project".equals(partitionKey) ? DerivationScopeMode.FULL_REPO : DerivationScopeMode.PATH_SET;
            if (scopeMode == DerivationScopeMode.PATH_SET && paths.isEmpty()) {
                effects.add(effect(
                        "SCOPE_RECORDED",
                        partitionKey,
                        null,
                        Map.of("scopeType", run.getScopeType().name())));
                continue;
            }
            var result = derivationService.run(new CreateDerivationRunCommand(
                    run.getProject().getId(),
                    scopeMode,
                    run.getCommitSha(),
                    run.getBaseCommitSha(),
                    paths,
                    run.getLanguages(),
                    run.getSurfaces(),
                    declaredBoundaries));
            effects.add(effect(
                    "DERIVATION_RUN",
                    partitionKey,
                    result.run().getId(),
                    Map.of(
                            "factCount", result.facts().size(),
                            "captureLimitCount", result.captureLimits().size())));
            var snapshot = result.architectureModel() == null
                    ? null
                    : result.architectureModel().snapshot();
            if (snapshot != null) {
                effects.add(effect(
                        "ARCHITECTURE_MODEL_SNAPSHOT",
                        partitionKey,
                        snapshot.getId(),
                        Map.of(
                                "modelVersion", snapshot.getModelVersion(),
                                "elementCount", snapshot.getElementCount(),
                                "flowCount", snapshot.getFlowCount())));
            }
        }
        run.recordGraphEffects(effects);
    }

    private List<Map<String, Object>> reScreenEffects(GrcAssessmentRun run) {
        var threatPackId = run.getThreatPackId();
        if (threatPackId == null || threatPackId.isBlank()) {
            return List.of(effect("RE_SCREEN", "all-partitions", null, Map.of("reason", "no_threat_pack")));
        }
        var version = run.getThreatPackVersion();
        var threats = threatEnumerationService.enumerateLatest(run.getProject().getId(), threatPackId, version);
        var controls = controlIdentificationService.identifyForLatestSnapshot(
                run.getProject().getId(), threatPackId, version);
        return List.of(
                effect(
                        "THREAT_ENUMERATION",
                        "all-partitions",
                        threats.snapshotId(),
                        Map.of(
                                "candidateCount", threats.candidates().size(),
                                "limitationCount", threats.limitations().size(),
                                "packId", threats.packId(),
                                "resolvedVersion", threats.resolvedVersion())),
                effect(
                        "CONTROL_IDENTIFICATION",
                        "all-partitions",
                        null,
                        Map.of(
                                "candidateCount", controls.candidates().size(),
                                "gapCount", controls.gaps().size(),
                                "ruleSetId", controls.ruleSetId(),
                                "ruleSetVersion", controls.ruleSetVersion())));
    }

    private static PartitionBuildResult buildPartitions(
            GrcAssessmentScopeType scopeType,
            List<String> scopeValues,
            List<BoundaryDeclaration> declaredBoundaries,
            int partitionLimit) {
        List<Map<String, Object>> requested = new ArrayList<>();
        var values = normalizeScopeValues(scopeValues);
        switch (scopeType) {
            case WHOLE_PROJECT -> requested.add(partition("whole-project", scopeType, "whole-project", List.of()));
            case STALE_DRIFT_SET -> requested.add(
                    partition("stale-drift-set", scopeType, "stale-drift-set", List.of()));
            case BOUNDARY -> {
                var declarations = new LinkedHashMap<String, BoundaryDeclaration>();
                for (var boundary : declaredBoundaries) {
                    declarations.put(boundary.key(), boundary);
                }
                for (var value : values) {
                    var boundary = declarations.get(value);
                    requested.add(partition(
                            "boundary:" + value,
                            scopeType,
                            value,
                            boundary == null ? List.of() : boundary.pathSelectors()));
                }
            }
            case PACKAGE_PATH_SET -> {
                for (var value : values) {
                    requested.add(partition("path:" + value, scopeType, value, List.of(value)));
                }
            }
            case ASSET -> {
                for (var value : values) {
                    requested.add(partition("asset:" + value, scopeType, value, List.of()));
                }
            }
            case NAMED_THREAT_SET -> {
                for (var value : values) {
                    requested.add(partition("threat-set:" + value, scopeType, value, List.of()));
                }
            }
            case NAMED_RISK_SET -> {
                for (var value : values) {
                    requested.add(partition("risk-set:" + value, scopeType, value, List.of()));
                }
            }
            default -> throw validation("Unsupported scopeType: " + scopeType, "scopeType");
        }
        if (requested.isEmpty()) {
            throw validation("scopeValues is required for " + scopeType, "scopeValues");
        }
        var unique = new LinkedHashMap<String, Map<String, Object>>();
        for (var partition : requested) {
            unique.putIfAbsent(string(partition.get("partitionKey")), partition);
        }
        var sorted = unique.values().stream()
                .sorted(Comparator.comparing(partition -> string(partition.get("partitionKey"))))
                .toList();
        if (sorted.size() > partitionLimit) {
            throw validation("partition count exceeds partitionLimit", "partitionLimit");
        }
        return new PartitionBuildResult(requested.size(), sorted);
    }

    private static Map<String, Object> partition(
            String partitionKey, GrcAssessmentScopeType scopeType, String scopeValue, List<String> paths) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("partitionKey", partitionKey);
        map.put("scopeType", scopeType.name());
        map.put("scopeValue", scopeValue);
        map.put("paths", paths == null ? List.of() : List.copyOf(paths));
        return map;
    }

    private static Map<String, Object> effect(
            String effectType, String partitionKey, Object effectId, Map<String, Object> extra) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("effectType", effectType);
        map.put("partitionKey", partitionKey);
        if (effectId != null) {
            map.put("effectId", effectId.toString());
        }
        if (extra != null) {
            map.putAll(extra);
        }
        return map;
    }

    private static Map<String, Object> boundaryToMap(BoundaryDeclaration boundary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", boundary.key());
        map.put("name", boundary.name());
        if (boundary.description() != null) {
            map.put("description", boundary.description());
        }
        map.put("pathSelectors", boundary.pathSelectors());
        map.put("surfaces", boundary.surfaces());
        return map;
    }

    private static List<BoundaryDeclaration> mapsToBoundaries(List<Map<String, Object>> maps) {
        return maps.stream()
                .map(map -> new BoundaryDeclaration(
                        string(map.get("key")),
                        string(map.get("name")),
                        string(map.get("description")),
                        stringList(map.get("pathSelectors")),
                        stringList(map.get("surfaces"))))
                .toList();
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw validation(field + " is required", field);
        }
        return value;
    }

    private static String normalizeCommit(String value, GrcAssessmentMode mode) {
        if (mode == GrcAssessmentMode.RE_SCREEN && (value == null || value.isBlank())) {
            return null;
        }
        return normalizeOptionalCommit(value, "commitSha");
    }

    private static String normalizeOptionalCommit(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!COMMIT_SHA.matcher(normalized).matches()) {
            throw validation(field + " must be a 7-64 character hexadecimal commit SHA", field);
        }
        return normalized;
    }

    private static List<String> normalizeTokens(List<String> values, String field, boolean required) {
        if (values == null || values.isEmpty()) {
            if (!required) {
                return List.of();
            }
            throw validation(field + " is required", field);
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> normalizeScopeValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static int partitionLimit(Integer value) {
        if (value == null) {
            return DEFAULT_PARTITION_LIMIT;
        }
        if (value < 1 || value > MAX_PARTITION_LIMIT) {
            throw validation("partitionLimit must be between 1 and " + MAX_PARTITION_LIMIT, "partitionLimit");
        }
        return value;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(Object::toString).toList();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DomainValidationException validation(String message, String field) {
        Map<String, Serializable> detail = new LinkedHashMap<>();
        detail.put("field", field);
        return new DomainValidationException(message, "validation_error", detail);
    }

    private record PartitionBuildResult(int requestedCount, List<Map<String, Object>> uniquePartitions) {}
}
