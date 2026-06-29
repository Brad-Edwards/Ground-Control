package com.keplerops.groundcontrol.domain.architecturemodel.service;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElement;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ArchitectureModelService {

    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{7,64}$");
    private static final Pattern STABLE_KEY = Pattern.compile("^[a-z0-9][a-z0-9:_.-]{0,199}$");
    private static final Pattern SOURCE_TOKEN = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final int MAX_ELEMENTS_PER_SNAPSHOT = 10_000;
    private static final String ELEMENTS_FIELD = "elements";
    private static final String ELEMENTS_FLOW_FIELD = "elements.flow";
    private static final String SOURCE_FIELD = "source";
    private static final Set<String> BLOCKED_METADATA_KEYS = Set.of(
            "secret",
            "secret_value",
            "secretvalue",
            "raw_secret",
            "rawsecret",
            "raw_output",
            "rawoutput",
            "stderr",
            "source_content",
            "sourcecontent",
            "raw_diff",
            "rawdiff",
            "token",
            "credential",
            "credentials");

    private final ArchitectureModelSnapshotRepository snapshotRepository;
    private final ArchitectureModelElementRepository elementRepository;
    private final ArchitectureModelElementStateRepository stateRepository;
    private final ProjectService projectService;

    public ArchitectureModelService(
            ArchitectureModelSnapshotRepository snapshotRepository,
            ArchitectureModelElementRepository elementRepository,
            ArchitectureModelElementStateRepository stateRepository,
            ProjectService projectService) {
        this.snapshotRepository = snapshotRepository;
        this.elementRepository = elementRepository;
        this.stateRepository = stateRepository;
        this.projectService = projectService;
    }

    public ArchitectureModelSnapshotView createSnapshot(CreateArchitectureModelSnapshotCommand command) {
        var project = projectService.getById(requireProjectId(command));
        return createSnapshot(project, null, normalizeCommand(command, null));
    }

    ArchitectureModelSnapshotView createSnapshot(
            Project project, DerivationRun derivationRun, CreateArchitectureModelSnapshotCommand command) {
        return persistSnapshot(project, derivationRun, normalizeCommand(command, derivationRun));
    }

    /**
     * Returns snapshot metadata only. Element states are intentionally not loaded here: the list endpoint
     * exposes summaries (counts), and the full per-element payload is served by {@link #getSnapshot} for a
     * single snapshot. This keeps the list response bounded regardless of snapshot history or element count.
     */
    @Transactional(readOnly = true)
    public List<ArchitectureModelSnapshot> listSnapshots(UUID projectId) {
        return snapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public ArchitectureModelSnapshotView getSnapshot(UUID projectId, UUID snapshotId) {
        var snapshot = findSnapshotOrThrow(projectId, snapshotId);
        return new ArchitectureModelSnapshotView(
                snapshot, stateRepository.findBySnapshotIdOrderByStableKey(snapshotId));
    }

    @Transactional(readOnly = true)
    public List<ArchitectureModelElementView> listElements(UUID projectId) {
        return stateRepository.findLatestSnapshotStatesByProjectId(projectId).stream()
                .map(state -> new ArchitectureModelElementView(state.getElement(), state))
                .toList();
    }

    @Transactional(readOnly = true)
    public ArchitectureModelElementView getElement(UUID projectId, UUID elementId) {
        var element = elementRepository
                .findByIdAndProjectId(elementId, projectId)
                .orElseThrow(() -> new NotFoundException("Architecture model element not found: " + elementId));
        var state = stateRepository
                .findLatestStateByElementIdAndProjectId(elementId, projectId)
                .orElse(null);
        return new ArchitectureModelElementView(element, state);
    }

    @Transactional(readOnly = true)
    public ArchitectureModelDiffResult diff(UUID projectId, UUID fromSnapshotId, UUID toSnapshotId) {
        var fromSnapshot = findSnapshotOrThrow(projectId, fromSnapshotId);
        var toSnapshot = findSnapshotOrThrow(projectId, toSnapshotId);
        var from = byStableKey(stateRepository.findBySnapshotIdOrderByStableKey(fromSnapshot.getId()));
        var to = byStableKey(stateRepository.findBySnapshotIdOrderByStableKey(toSnapshot.getId()));
        var keys = new LinkedHashSet<String>();
        keys.addAll(from.keySet());
        keys.addAll(to.keySet());
        var entries = keys.stream()
                .sorted()
                .map(key -> diffEntry(key, from.get(key), to.get(key)))
                .toList();
        return new ArchitectureModelDiffResult(fromSnapshotId, toSnapshotId, entries);
    }

    public ArchitectureModelSnapshotView buildFromDerivation(
            Project project, DerivationRun run, List<SystemModelFact> facts) {
        var commands = facts == null
                ? List.<ArchitectureModelElementStateCommand>of()
                : facts.stream()
                        .map(fact -> toArchitectureModelCommand(run, fact))
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(ArchitectureModelElementStateCommand::stableKey))
                        .toList();
        if (commands.isEmpty()) {
            return null;
        }
        var modelVersion = "architecture-model/"
                + run.getCommitSha()
                        .substring(0, Math.min(12, run.getCommitSha().length()))
                + "/"
                + run.getId().toString().substring(0, 8);
        var command = new CreateArchitectureModelSnapshotCommand(
                project.getId(), modelVersion, run.getCommitSha(), "DERIVATION", run.getRequestedBy(), commands);
        return createSnapshot(project, run, command);
    }

    private ArchitectureModelSnapshotView persistSnapshot(
            Project project, DerivationRun derivationRun, CreateArchitectureModelSnapshotCommand command) {
        if (snapshotRepository.existsByProjectIdAndModelVersion(project.getId(), command.modelVersion())) {
            throw validation("architecture model snapshot version already exists", "modelVersion");
        }
        validateSnapshotElements(command.elements());
        var snapshot = snapshotRepository.save(new ArchitectureModelSnapshot(
                project,
                derivationRun,
                command.modelVersion(),
                command.commitSha(),
                command.source(),
                command.createdBy()));
        var states =
                new ArrayList<ArchitectureModelElementState>(command.elements().size());
        for (var elementCommand : command.elements()) {
            var element = upsertElement(project, elementCommand);
            states.add(new ArchitectureModelElementState(project, snapshot, element, elementCommand));
        }
        snapshot.setCounts(states.size(), (int) states.stream()
                .filter(state -> state.getElementKind() == ArchitectureModelElementKind.DATA_FLOW)
                .count());
        var savedStates = new ArrayList<ArchitectureModelElementState>();
        stateRepository.saveAll(states).forEach(savedStates::add);
        return new ArchitectureModelSnapshotView(snapshot, savedStates);
    }

    private ArchitectureModelElement upsertElement(Project project, ArchitectureModelElementStateCommand command) {
        return elementRepository
                .findByProjectIdAndStableKey(project.getId(), command.stableKey())
                .map(existing -> {
                    if (existing.getElementKind() != command.elementKind()) {
                        throw validation(
                                "architecture model element kind cannot change for a stable key", ELEMENTS_FIELD);
                    }
                    return existing;
                })
                .orElseGet(() -> elementRepository.save(
                        new ArchitectureModelElement(project, command.stableKey(), command.elementKind())));
    }

    private CreateArchitectureModelSnapshotCommand normalizeCommand(
            CreateArchitectureModelSnapshotCommand command, DerivationRun derivationRun) {
        if (command == null) {
            throw validation("architecture model snapshot command is required", "snapshot");
        }
        var projectId = requireProjectId(command);
        var modelVersion = requireLength(command.modelVersion(), "modelVersion", 120);
        var commitSha = normalizeCommitSha(command.commitSha(), "commitSha");
        var source = normalizeSource(command.source());
        var elements = command.elements();
        if (elements.isEmpty()) {
            throw validation("architecture model snapshots require at least one element", ELEMENTS_FIELD);
        }
        if (elements.size() > MAX_ELEMENTS_PER_SNAPSHOT) {
            throw validation("architecture model snapshot contains too many elements", ELEMENTS_FIELD);
        }
        var normalized = new ArrayList<ArchitectureModelElementStateCommand>(elements.size());
        for (var element : elements) {
            normalized.add(normalizeElement(element, commitSha, derivationRun));
        }
        return new CreateArchitectureModelSnapshotCommand(
                projectId, modelVersion, commitSha, source, trimToNull(command.createdBy()), normalized);
    }

    private UUID requireProjectId(CreateArchitectureModelSnapshotCommand command) {
        if (command == null || command.projectId() == null) {
            throw validation("projectId is required", "projectId");
        }
        return command.projectId();
    }

    private ArchitectureModelElementStateCommand normalizeElement(
            ArchitectureModelElementStateCommand command, String snapshotCommitSha, DerivationRun derivationRun) {
        if (command == null) {
            throw validation("architecture model element is required", ELEMENTS_FIELD);
        }
        var stableKey = normalizeStableKey(command.stableKey(), "elements.stableKey");
        var kind = command.elementKind();
        if (kind == null) {
            throw validation("elementKind is required", "elements.elementKind");
        }
        var label = requireLength(command.label(), "elements.label", 200);
        var summary = trimMax(command.summary(), "elements.summary", 8192);
        var sourcePath = trimMax(command.sourcePath(), "elements.sourcePath", 500);
        var trustBoundaryKey = normalizeOptionalStableKey(command.trustBoundaryKey(), "elements.trustBoundaryKey");
        var dataClassificationKey =
                normalizeOptionalStableKey(command.dataClassificationKey(), "elements.dataClassificationKey");
        var flowSourceStableKey =
                normalizeOptionalStableKey(command.flowSourceStableKey(), "elements.flowSourceStableKey");
        var flowTargetStableKey =
                normalizeOptionalStableKey(command.flowTargetStableKey(), "elements.flowTargetStableKey");
        var flowDirection =
                normalizeFlowFields(kind, flowSourceStableKey, flowTargetStableKey, command.flowDirection());
        if (command.provenanceSource() == null) {
            throw validation("provenanceSource is required", "elements.provenanceSource");
        }
        var provenanceKey = requireLength(command.provenanceKey(), "elements.provenanceKey", 200);
        var commitSha = command.commitSha() == null || command.commitSha().isBlank()
                ? snapshotCommitSha
                : normalizeCommitSha(command.commitSha(), "elements.commitSha");
        var metadata = command.metadata() == null ? Map.<String, Object>of() : command.metadata();
        rejectSensitiveMetadataKeys(metadata, "elements.metadata");
        return new ArchitectureModelElementStateCommand(
                stableKey,
                kind,
                label,
                summary,
                sourcePath,
                trustBoundaryKey,
                dataClassificationKey,
                flowSourceStableKey,
                flowTargetStableKey,
                flowDirection,
                command.provenanceSource(),
                provenanceKey,
                trimMax(command.adapterId(), "elements.adapterId", 100),
                trimMax(command.toolName(), "elements.toolName", 100),
                trimMax(command.toolVersion(), "elements.toolVersion", 100),
                trimMax(command.rulesetName(), "elements.rulesetName", 200),
                trimMax(command.rulesetVersion(), "elements.rulesetVersion", 100),
                command.derivationRunId() == null && derivationRun != null
                        ? derivationRun.getId()
                        : command.derivationRunId(),
                commitSha,
                metadata);
    }

    private ArchitectureFlowDirection normalizeFlowFields(
            ArchitectureModelElementKind kind,
            String flowSourceStableKey,
            String flowTargetStableKey,
            ArchitectureFlowDirection flowDirection) {
        if (kind == ArchitectureModelElementKind.DATA_FLOW) {
            if (flowSourceStableKey == null || flowTargetStableKey == null) {
                throw validation("DATA_FLOW elements require flow source and target stable keys", ELEMENTS_FLOW_FIELD);
            }
            return flowDirection == null ? ArchitectureFlowDirection.UNIDIRECTIONAL : flowDirection;
        }
        if (flowSourceStableKey != null || flowTargetStableKey != null || flowDirection != null) {
            throw validation("flow fields are only allowed on DATA_FLOW elements", ELEMENTS_FLOW_FIELD);
        }
        return null;
    }

    private void validateSnapshotElements(List<ArchitectureModelElementStateCommand> elements) {
        var stableKeys = new LinkedHashSet<String>();
        for (var element : elements) {
            if (!stableKeys.add(element.stableKey())) {
                throw validation("architecture model snapshot contains duplicate stable keys", "elements.stableKey");
            }
        }
        for (var element : elements) {
            if (element.elementKind() == ArchitectureModelElementKind.DATA_FLOW
                    && (!stableKeys.contains(element.flowSourceStableKey())
                            || !stableKeys.contains(element.flowTargetStableKey()))) {
                throw validation("DATA_FLOW endpoints must exist in the same snapshot", ELEMENTS_FLOW_FIELD);
            }
        }
    }

    private ArchitectureModelSnapshot findSnapshotOrThrow(UUID projectId, UUID snapshotId) {
        if (snapshotId == null) {
            throw validation("snapshot id is required", "snapshotId");
        }
        return snapshotRepository
                .findByIdAndProjectId(snapshotId, projectId)
                .orElseThrow(() -> new NotFoundException("Architecture model snapshot not found: " + snapshotId));
    }

    private Map<String, ArchitectureModelElementState> byStableKey(List<ArchitectureModelElementState> states) {
        var byKey = new LinkedHashMap<String, ArchitectureModelElementState>();
        for (var state : states) {
            byKey.put(state.getStableKey(), state);
        }
        return byKey;
    }

    private ArchitectureModelDiffEntry diffEntry(
            String stableKey, ArchitectureModelElementState from, ArchitectureModelElementState to) {
        if (from == null) {
            return new ArchitectureModelDiffEntry(stableKey, ArchitectureModelDiffStatus.ADDED, "Element added");
        }
        if (to == null) {
            return new ArchitectureModelDiffEntry(stableKey, ArchitectureModelDiffStatus.REMOVED, "Element removed");
        }
        if (!coreState(from).equals(coreState(to))) {
            return new ArchitectureModelDiffEntry(stableKey, ArchitectureModelDiffStatus.CHANGED, "Element changed");
        }
        if (!provenanceState(from).equals(provenanceState(to))) {
            return new ArchitectureModelDiffEntry(
                    stableKey, ArchitectureModelDiffStatus.PROVENANCE_ONLY_CHANGED, "Element provenance changed");
        }
        return new ArchitectureModelDiffEntry(stableKey, ArchitectureModelDiffStatus.UNCHANGED, "Element unchanged");
    }

    private List<Object> coreState(ArchitectureModelElementState state) {
        return List.of(
                state.getElementKind(),
                state.getLabel(),
                nullable(state.getSummary()),
                nullable(state.getSourcePath()),
                nullable(state.getTrustBoundaryKey()),
                nullable(state.getDataClassificationKey()),
                nullable(state.getFlowSourceStableKey()),
                nullable(state.getFlowTargetStableKey()),
                nullable(state.getFlowDirection()),
                state.getMetadata());
    }

    private List<Object> provenanceState(ArchitectureModelElementState state) {
        return List.of(
                state.getProvenanceSource(),
                state.getProvenanceKey(),
                nullable(state.getAdapterId()),
                nullable(state.getToolName()),
                nullable(state.getToolVersion()),
                nullable(state.getRulesetName()),
                nullable(state.getRulesetVersion()),
                nullable(state.getDerivationRunId()),
                state.getCommitSha());
    }

    private Object nullable(Object value) {
        return value == null ? "" : value;
    }

    private ArchitectureModelElementStateCommand toArchitectureModelCommand(DerivationRun run, SystemModelFact fact) {
        var payload = fact.getPayload();
        var kind =
                switch (fact.getFactKind()) {
                    case COMPONENT -> elementKindFromPayload(payload);
                    case TRUST_BOUNDARY -> ArchitectureModelElementKind.TRUST_BOUNDARY;
                    case DATA_FLOW -> ArchitectureModelElementKind.DATA_FLOW;
                    case ENTRY_POINT -> ArchitectureModelElementKind.PROCESS;
                    case EXTERNAL_INTERACTION -> ArchitectureModelElementKind.EXTERNAL_ENTITY;
                    case DATA_CLASSIFICATION_HINT -> ArchitectureModelElementKind.DATA_CLASSIFICATION;
                    case TAINT_PATH, SECRET_USAGE -> null;
                };
        if (kind == null) {
            return null;
        }
        var stableKey = normalizeStableKeyForDerivation(fact.getFactKey());
        var sourceKey = kind == ArchitectureModelElementKind.DATA_FLOW
                ? normalizeNullableStableKeyForDerivation(
                        stringAt(payload, "sourceStableKey", "sourceKey", SOURCE_FIELD))
                : null;
        var targetKey = kind == ArchitectureModelElementKind.DATA_FLOW
                ? normalizeNullableStableKeyForDerivation(stringAt(payload, "targetStableKey", "targetKey", "target"))
                : null;
        if (kind == ArchitectureModelElementKind.DATA_FLOW && (sourceKey == null || targetKey == null)) {
            return null;
        }
        return new ArchitectureModelElementStateCommand(
                stableKey,
                kind,
                fact.getLabel(),
                fact.getSummary(),
                fact.getSourcePath(),
                normalizeNullableStableKeyForDerivation(stringAt(payload, "trustBoundaryKey", "boundaryKey")),
                normalizeNullableStableKeyForDerivation(
                        stringAt(payload, "dataClassificationKey", "classificationKey", "classification")),
                sourceKey,
                targetKey,
                kind == ArchitectureModelElementKind.DATA_FLOW ? flowDirectionFromPayload(payload) : null,
                ArchitectureModelProvenanceSource.ADAPTER,
                fact.getFactKey(),
                fact.getAdapterId(),
                fact.getToolName(),
                fact.getToolVersion(),
                fact.getRulesetName(),
                fact.getRulesetVersion(),
                run.getId(),
                fact.getCommitSha(),
                payload);
    }

    private ArchitectureModelElementKind elementKindFromPayload(Map<String, Object> payload) {
        var declared = stringAt(payload, "elementKind", "dfdKind", "kind");
        if (declared != null) {
            try {
                return ArchitectureModelElementKind.valueOf(declared.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ArchitectureModelElementKind.COMPONENT;
            }
        }
        return ArchitectureModelElementKind.COMPONENT;
    }

    private ArchitectureFlowDirection flowDirectionFromPayload(Map<String, Object> payload) {
        var declared = stringAt(payload, "flowDirection", "direction");
        if (declared != null) {
            try {
                return ArchitectureFlowDirection.valueOf(declared.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ArchitectureFlowDirection.UNIDIRECTIONAL;
            }
        }
        return ArchitectureFlowDirection.UNIDIRECTIONAL;
    }

    private String stringAt(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            var value = payload.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private String normalizeStableKeyForDerivation(String value) {
        var normalized =
                value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9:_.-]", "-");
        while (normalized.startsWith("-")
                || normalized.startsWith("_")
                || normalized.startsWith(":")
                || normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            normalized = "fact:" + shortDigest(value == null ? "" : value);
        }
        if (normalized.length() > 200) {
            normalized = normalized.substring(0, 187) + ":" + shortDigest(normalized);
        }
        return normalized;
    }

    private String normalizeNullableStableKeyForDerivation(String value) {
        return value == null ? null : normalizeStableKeyForDerivation(value);
    }

    private String shortDigest(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeCommitSha(String value, String field) {
        if (value == null
                || value.isBlank()
                || !COMMIT_SHA.matcher(value.trim()).matches()) {
            throw validation(field + " must be a 7-64 character hexadecimal commit SHA", field);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSource(String value) {
        var source = requireLength(value, SOURCE_FIELD, 40).toUpperCase(Locale.ROOT);
        if (!SOURCE_TOKEN.matcher(source).matches()) {
            throw validation("source must be an uppercase token", SOURCE_FIELD);
        }
        return source;
    }

    private String normalizeStableKey(String value, String field) {
        var stableKey = requireLength(value, field, 200).toLowerCase(Locale.ROOT);
        if (!STABLE_KEY.matcher(stableKey).matches()) {
            throw validation("stable keys must match [a-z0-9][a-z0-9:_.-]{0,199}", field);
        }
        return stableKey;
    }

    private String normalizeOptionalStableKey(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeStableKey(value, field);
    }

    private String requireLength(String value, String field, int maxLength) {
        var trimmed = trimToNull(value);
        if (trimmed == null) {
            throw validation(field + " is required", field);
        }
        if (trimmed.length() > maxLength) {
            throw validation(field + " must contain at most " + maxLength + " characters", field);
        }
        return trimmed;
    }

    private String trimMax(String value, String field, int maxLength) {
        var trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > maxLength) {
            throw validation(field + " must contain at most " + maxLength + " characters", field);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private void rejectSensitiveMetadataKeys(Map<String, Object> metadata, String path) {
        for (var entry : metadata.entrySet()) {
            var key = entry.getKey() == null
                    ? ""
                    : entry.getKey().replace("-", "_").toLowerCase(Locale.ROOT);
            if (BLOCKED_METADATA_KEYS.contains(key)) {
                throw validation("architecture model metadata contains a blocked raw-content field", path);
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                rejectSensitiveMetadataKeys((Map<String, Object>) nested, path + "." + entry.getKey());
            }
        }
    }

    private DomainValidationException validation(String message, String field) {
        Map<String, Serializable> detail = new LinkedHashMap<>();
        detail.put("field", field);
        return new DomainValidationException(message, "validation_error", detail);
    }
}
