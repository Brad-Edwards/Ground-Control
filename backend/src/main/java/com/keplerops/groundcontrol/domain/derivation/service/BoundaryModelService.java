package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelAssignment;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelBoundary;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelGap;
import com.keplerops.groundcontrol.domain.derivation.model.BoundaryModelSnapshot;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelAssignmentRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelBoundaryRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelGapRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoundaryModelService {

    private static final Pattern BOUNDARY_KEY = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,119}$");
    private static final Set<SystemModelFactKind> ASSIGNABLE_FACT_KINDS = Set.of(
            SystemModelFactKind.COMPONENT,
            SystemModelFactKind.DATA_FLOW,
            SystemModelFactKind.ENTRY_POINT,
            SystemModelFactKind.TAINT_PATH,
            SystemModelFactKind.SECRET_USAGE,
            SystemModelFactKind.EXTERNAL_INTERACTION);

    private final BoundaryModelSnapshotRepository snapshotRepository;
    private final BoundaryModelBoundaryRepository boundaryRepository;
    private final BoundaryModelAssignmentRepository assignmentRepository;
    private final BoundaryModelGapRepository gapRepository;

    public BoundaryModelService(
            BoundaryModelSnapshotRepository snapshotRepository,
            BoundaryModelBoundaryRepository boundaryRepository,
            BoundaryModelAssignmentRepository assignmentRepository,
            BoundaryModelGapRepository gapRepository) {
        this.snapshotRepository = snapshotRepository;
        this.boundaryRepository = boundaryRepository;
        this.assignmentRepository = assignmentRepository;
        this.gapRepository = gapRepository;
    }

    @Transactional
    public BoundaryModelBuildResult build(
            Project project,
            DerivationRun run,
            List<SystemModelFact> facts,
            List<BoundaryDeclaration> declaredBoundaries) {
        var inputs = canonicalInputs(facts, declaredBoundaries);
        List<SystemModelFact> normalizedFacts = facts == null ? List.of() : facts;
        if (inputs.isEmpty() && normalizedFacts.stream().noneMatch(this::isAssignable)) {
            return null;
        }
        var declarationDigest = digest(declaredBoundaries == null ? List.of() : declaredBoundaries);
        var boundarySetVersion = "boundary-set/" + digest(inputs).substring(0, 24);
        var commitPrefix =
                run.getCommitSha().substring(0, Math.min(12, run.getCommitSha().length()));
        var architectureModelVersion =
                "architecture-model/" + commitPrefix + "/" + boundarySetVersion.substring("boundary-set/".length());
        var snapshot = snapshotRepository.save(new BoundaryModelSnapshot(
                project, run, boundarySetVersion, architectureModelVersion, declarationDigest));

        var boundaries = boundaryRepository.saveAll(inputs.stream()
                .map(input -> new BoundaryModelBoundary(
                        project,
                        snapshot,
                        input.key(),
                        input.name(),
                        input.description(),
                        input.source(),
                        input.pathSelectors(),
                        input.surfaces(),
                        input.inputFactKeys()))
                .toList());

        var byKey = new LinkedHashMap<String, BoundaryModelBoundary>();
        for (BoundaryModelBoundary boundary : boundaries) {
            byKey.put(boundary.getBoundaryKey(), boundary);
        }
        var coverage = buildCoverage(project, snapshot, normalizedFacts, byKey);
        var assignments = assignmentRepository.saveAll(coverage.assignments());
        var gaps = gapRepository.saveAll(coverage.gaps());
        snapshot.setCounts(boundaries.size(), assignments.size(), gaps.size());
        snapshotRepository.save(snapshot);
        return new BoundaryModelBuildResult(snapshot, boundaries, assignments, gaps);
    }

    @Transactional(readOnly = true)
    public BoundaryModelBuildResult get(UUID projectId, UUID derivationRunId) {
        var snapshot = snapshotRepository
                .findByProjectIdAndDerivationRunId(projectId, derivationRunId)
                .orElseThrow(() -> new NotFoundException(
                        "Boundary model snapshot not found for derivation run: " + derivationRunId));
        return new BoundaryModelBuildResult(
                snapshot,
                boundaryRepository.findBySnapshotIdOrderByBoundaryKey(snapshot.getId()),
                assignmentRepository.findBySnapshotIdOrderBySourceFactKey(snapshot.getId()),
                gapRepository.findBySnapshotIdOrderBySourceFactKey(snapshot.getId()));
    }

    private List<BoundaryInput> canonicalInputs(List<SystemModelFact> facts, List<BoundaryDeclaration> declarations) {
        var byKey = new LinkedHashMap<String, BoundaryInputBuilder>();
        if (facts != null) {
            facts.stream()
                    .filter(fact -> fact.getFactKind() == SystemModelFactKind.TRUST_BOUNDARY)
                    .forEach(fact -> inputFromFact(fact).ifPresent(input -> merge(byKey, input)));
        }
        if (declarations != null) {
            declarations.forEach(declaration -> merge(byKey, inputFromDeclaration(declaration)));
        }
        return byKey.values().stream()
                .map(BoundaryInputBuilder::build)
                .sorted(Comparator.comparing(BoundaryInput::key))
                .toList();
    }

    private java.util.Optional<BoundaryInput> inputFromFact(SystemModelFact fact) {
        var payload = fact.getPayload();
        var key = stringValue(payload.get("boundaryKey"));
        if (key == null) {
            return java.util.Optional.empty();
        }
        validateBoundaryKey(key, "facts.payload.boundaryKey");
        var selectors = stringList(payload.get("pathSelectors"));
        if (selectors.isEmpty()) {
            throw validation("Derived boundary fact must include at least one path selector", "pathSelectors");
        }
        return java.util.Optional.of(new BoundaryInput(
                key,
                stringValue(payload.get("boundaryName"), key),
                stringValue(payload.get("description"), fact.getSummary()),
                "DERIVED",
                selectors,
                stringList(payload.get("surfaces")),
                List.of(fact.getFactKey())));
    }

    private BoundaryInput inputFromDeclaration(BoundaryDeclaration declaration) {
        if (declaration == null) {
            throw validation("Declared boundary must be a mapping", "declaredBoundaries");
        }
        var key = trim(declaration.key());
        validateBoundaryKey(key, "declaredBoundaries.key");
        var name = trim(declaration.name());
        if (name == null) {
            throw validation("Declared boundary name is required", "declaredBoundaries.name");
        }
        if (declaration.pathSelectors().isEmpty()) {
            throw validation("Declared boundary pathSelectors must not be empty", "declaredBoundaries.pathSelectors");
        }
        return new BoundaryInput(
                key,
                name,
                trim(declaration.description()),
                "DECLARED",
                declaration.pathSelectors(),
                declaration.surfaces(),
                List.of());
    }

    private CoverageDraft buildCoverage(
            Project project,
            BoundaryModelSnapshot snapshot,
            List<SystemModelFact> facts,
            Map<String, BoundaryModelBoundary> boundaries) {
        var assignments = new ArrayList<BoundaryModelAssignment>();
        var gaps = new ArrayList<BoundaryModelGap>();
        facts.stream()
                .filter(this::isAssignable)
                .forEach(fact -> assignFact(project, snapshot, boundaries.values(), assignments, gaps, fact));
        return new CoverageDraft(assignments, gaps);
    }

    private boolean isAssignable(SystemModelFact fact) {
        return fact != null && ASSIGNABLE_FACT_KINDS.contains(fact.getFactKind());
    }

    private void assignFact(
            Project project,
            BoundaryModelSnapshot snapshot,
            Iterable<BoundaryModelBoundary> boundaries,
            List<BoundaryModelAssignment> assignments,
            List<BoundaryModelGap> gaps,
            SystemModelFact fact) {
        var sourcePath = trim(fact.getSourcePath());
        if (sourcePath == null) {
            gaps.add(gap(project, snapshot, fact, null, "MISSING_SOURCE_PATH", "Derived fact has no source path"));
            return;
        }
        var matches = new ArrayList<BoundaryModelBoundary>();
        for (BoundaryModelBoundary boundary : boundaries) {
            if (matchesAny(sourcePath, boundary.getPathSelectors())) {
                matches.add(boundary);
            }
        }
        if (matches.isEmpty()) {
            gaps.add(gap(
                    project,
                    snapshot,
                    fact,
                    sourcePath,
                    "UNASSIGNED_BOUNDARY",
                    "No canonical boundary selector matched source path"));
            return;
        }
        if (matches.size() > 1) {
            gaps.add(gap(
                    project,
                    snapshot,
                    fact,
                    sourcePath,
                    "AMBIGUOUS_BOUNDARY",
                    "Multiple canonical boundary selectors matched source path"));
            return;
        }
        assignments.add(new BoundaryModelAssignment(
                project,
                snapshot,
                matches.getFirst(),
                fact.getFactKey(),
                fact.getFactKind().name(),
                sourcePath,
                "PATH_SELECTOR"));
    }

    private BoundaryModelGap gap(
            Project project,
            BoundaryModelSnapshot snapshot,
            SystemModelFact fact,
            String sourcePath,
            String reason,
            String detail) {
        return new BoundaryModelGap(
                project, snapshot, fact.getFactKey(), fact.getFactKind().name(), sourcePath, reason, detail);
    }

    private boolean matchesAny(String sourcePath, List<String> selectors) {
        for (String selector : selectors) {
            if (matches(sourcePath, selector)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String sourcePath, String selector) {
        var normalizedPath = normalizePath(sourcePath);
        var normalizedSelector = normalizePath(selector);
        if (normalizedSelector.endsWith("/**")) {
            var prefix = normalizedSelector.substring(0, normalizedSelector.length() - 3);
            return normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/");
        }
        return normalizedPath.equals(normalizedSelector) || normalizedPath.startsWith(normalizedSelector + "/");
    }

    private void merge(Map<String, BoundaryInputBuilder> byKey, BoundaryInput input) {
        byKey.computeIfAbsent(input.key(), BoundaryInputBuilder::new).merge(input);
    }

    private void validateBoundaryKey(String key, String field) {
        if (key == null || !BOUNDARY_KEY.matcher(key).matches()) {
            throw validation(field + " must match " + BOUNDARY_KEY.pattern(), field);
        }
    }

    private DomainValidationException validation(String message, String field) {
        return new DomainValidationException(message, "validation_error", Map.of("field", field));
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String stringValue(Object value) {
        return stringValue(value, null);
    }

    private static String stringValue(Object value, String fallback) {
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                result.add(text.trim());
            }
        }
        return List.copyOf(result);
    }

    private static String normalizePath(String path) {
        var normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String digest(Object value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm unavailable", exception);
        }
    }

    private record CoverageDraft(List<BoundaryModelAssignment> assignments, List<BoundaryModelGap> gaps) {}

    private record BoundaryInput(
            String key,
            String name,
            String description,
            String source,
            List<String> pathSelectors,
            List<String> surfaces,
            List<String> inputFactKeys) {

        @Override
        public String toString() {
            return key + "|" + name + "|" + description + "|" + source + "|" + String.join(",", pathSelectors) + "|"
                    + String.join(",", surfaces);
        }
    }

    private static final class BoundaryInputBuilder {

        private final String key;
        private String name;
        private String description;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private final LinkedHashSet<String> pathSelectors = new LinkedHashSet<>();
        private final LinkedHashSet<String> surfaces = new LinkedHashSet<>();
        private final LinkedHashSet<String> inputFactKeys = new LinkedHashSet<>();

        BoundaryInputBuilder(String key) {
            this.key = key;
        }

        void merge(BoundaryInput input) {
            if ("DECLARED".equals(input.source())) {
                this.name = input.name();
                this.description = input.description();
            } else {
                if (this.name == null) {
                    this.name = input.name();
                }
                if (this.description == null) {
                    this.description = input.description();
                }
            }
            this.sources.add(input.source());
            this.pathSelectors.addAll(input.pathSelectors());
            this.surfaces.addAll(input.surfaces());
            this.inputFactKeys.addAll(input.inputFactKeys());
        }

        BoundaryInput build() {
            var source =
                    sources.size() > 1 ? "MERGED" : sources.stream().findFirst().orElse("DECLARED");
            return new BoundaryInput(
                    key,
                    name == null ? key : name,
                    description,
                    source,
                    List.copyOf(pathSelectors),
                    List.copyOf(surfaces),
                    List.copyOf(inputFactKeys));
        }
    }
}
