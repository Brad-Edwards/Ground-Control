package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelService;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationCaptureLimit;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.repository.DerivationCaptureLimitRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.DerivationRunRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.SystemModelFactRepository;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DerivationService {

    private static final Logger log = LoggerFactory.getLogger(DerivationService.class);
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{7,64}$");
    private static final Pattern SCOPE_TOKEN = Pattern.compile("^[a-z0-9][a-z0-9_.+-]{0,79}$");
    private static final int MAX_SCOPE_VALUES = 50;
    private static final int MAX_PATHS = 200;
    private static final String PATHS_FIELD = "paths";
    private static final Set<String> BLOCKED_PAYLOAD_KEYS = Set.of(
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
            "rawdiff");

    private final DerivationRunRepository runRepository;
    private final SystemModelFactRepository factRepository;
    private final DerivationCaptureLimitRepository captureLimitRepository;
    private final ProjectService projectService;
    private final DerivationAdapterRegistry adapterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final BoundaryModelService boundaryModelService;
    private final ArchitectureModelService architectureModelService;

    public DerivationService(
            DerivationRunRepository runRepository,
            SystemModelFactRepository factRepository,
            DerivationCaptureLimitRepository captureLimitRepository,
            ProjectService projectService,
            DerivationAdapterRegistry adapterRegistry,
            TransactionTemplate transactionTemplate,
            BoundaryModelService boundaryModelService,
            ArchitectureModelService architectureModelService) {
        this.runRepository = runRepository;
        this.factRepository = factRepository;
        this.captureLimitRepository = captureLimitRepository;
        this.projectService = projectService;
        this.adapterRegistry = adapterRegistry;
        this.transactionTemplate = transactionTemplate;
        this.boundaryModelService = boundaryModelService;
        this.architectureModelService = architectureModelService;
    }

    public DerivationRunResult run(CreateDerivationRunCommand command) {
        var project = projectService.getById(command.projectId());
        var scope = normalizeScope(command);
        var requestedAt = Instant.now();
        var routePlan = adapterRegistry.route(scope, requestedAt);

        var derivedFacts = new ArrayList<DerivedSystemModelFact>();
        var captureLimitDrafts = new ArrayList<>(routePlan.captureLimits());
        var adapterRequest = new DerivationAdapterRequest(project.getId(), project.getIdentifier(), scope);
        for (DerivationAdapter adapter : routePlan.adapters()) {
            try {
                var result = adapter.derive(adapterRequest);
                derivedFacts.addAll(result.facts());
                captureLimitDrafts.addAll(result.captureLimits());
            } catch (RuntimeException exception) {
                log.warn(
                        "derivation_adapter_failed: project={} adapterId={} scopeMode={}",
                        project.getIdentifier(),
                        adapter.descriptor().adapterId(),
                        scope.mode());
                captureLimitDrafts.add(new DerivationCaptureLimitDraft(
                        adapter.descriptor().adapterId(),
                        CaptureLimitReason.TOOL_EXECUTION_FAILED,
                        first(scope.languages()),
                        first(scope.surfaces()),
                        "Adapter execution failed; raw tool output was not persisted",
                        scope.commitSha(),
                        Instant.now()));
            }
        }

        var validatedFacts =
                derivedFacts.stream().map(fact -> validateFact(fact, scope)).toList();
        var validatedCaptureLimits = captureLimitDrafts.stream()
                .map(limit -> validateCaptureLimit(limit, scope))
                .toList();
        var actor = ActorHolder.get();
        var derivationData =
                new PersistedDerivationData(validatedFacts, validatedCaptureLimits, command.declaredBoundaries());
        var context = new PersistRunContext(
                command.projectId(), scope, requestedAt, routePlan.adapters().size(), actor, derivationData);
        var result = transactionTemplate.execute(status -> persistRunResult(context));
        if (result == null) {
            throw new IllegalStateException("Derivation run transaction returned no result");
        }
        return result;
    }

    private DerivationRunResult persistRunResult(PersistRunContext context) {
        var project = projectService.getById(context.projectId());
        var savedRun = runRepository.save(new DerivationRun(
                project,
                context.scope().mode(),
                context.scope().commitSha(),
                context.scope().baseCommitSha(),
                context.scope().paths(),
                List.copyOf(context.scope().languages()),
                List.copyOf(context.scope().surfaces()),
                context.actor(),
                context.requestedAt(),
                context.adapterCount()));

        var persistedRun = savedRun;
        var factRows = context.derivationData().validatedFacts().stream()
                .map(fact -> new SystemModelFact(project, persistedRun, fact))
                .toList();
        var captureLimitRows = context.derivationData().validatedCaptureLimits().stream()
                .map(limit -> new DerivationCaptureLimit(project, persistedRun, limit))
                .toList();
        factRows = factRepository.saveAll(factRows);
        captureLimitRows = captureLimitRepository.saveAll(captureLimitRows);
        var architectureModel = architectureModelService.buildFromDerivation(project, savedRun, factRows);
        var boundaryModel = boundaryModelService.build(
                project, savedRun, factRows, context.derivationData().declaredBoundaries());
        savedRun.setResultCounts(factRows.size(), captureLimitRows.size());
        savedRun = runRepository.save(savedRun);

        log.info(
                "derivation_run_created: project={} runId={} adapters={} facts={} captureLimits={}",
                project.getIdentifier(),
                savedRun.getId(),
                savedRun.getAdapterCount(),
                factRows.size(),
                captureLimitRows.size());
        return new DerivationRunResult(savedRun, factRows, captureLimitRows, architectureModel, boundaryModel);
    }

    private record PersistRunContext(
            UUID projectId,
            DerivationScope scope,
            Instant requestedAt,
            int adapterCount,
            String actor,
            PersistedDerivationData derivationData) {}

    private record PersistedDerivationData(
            List<DerivedSystemModelFact> validatedFacts,
            List<DerivationCaptureLimitDraft> validatedCaptureLimits,
            List<BoundaryDeclaration> declaredBoundaries) {}

    @Transactional(readOnly = true)
    public BoundaryModelBuildResult getBoundaryModel(UUID projectId, UUID runId) {
        findRunOrThrow(projectId, runId);
        return boundaryModelService.get(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<DerivationRun> listRuns(UUID projectId) {
        return runRepository.findByProjectIdOrderByRequestedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public DerivationRun getRun(UUID projectId, UUID runId) {
        return findRunOrThrow(projectId, runId);
    }

    @Transactional(readOnly = true)
    public List<SystemModelFact> listFacts(UUID projectId, UUID runId, SystemModelFactKind factKind) {
        if (runId != null && factKind != null) {
            findRunOrThrow(projectId, runId);
            return factRepository.findByProjectIdAndDerivationRunIdAndFactKindOrderByDerivedAtDesc(
                    projectId, runId, factKind);
        }
        if (runId != null) {
            findRunOrThrow(projectId, runId);
            return factRepository.findByProjectIdAndDerivationRunIdOrderByDerivedAtDesc(projectId, runId);
        }
        if (factKind != null) {
            return factRepository.findByProjectIdAndFactKindOrderByDerivedAtDesc(projectId, factKind);
        }
        return factRepository.findByProjectIdOrderByDerivedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<DerivationCaptureLimit> listCaptureLimits(UUID projectId, UUID runId, CaptureLimitReason reason) {
        if (runId != null && reason != null) {
            findRunOrThrow(projectId, runId);
            return captureLimitRepository.findByProjectIdAndDerivationRunIdAndReasonOrderByCapturedAtDesc(
                    projectId, runId, reason);
        }
        if (runId != null) {
            findRunOrThrow(projectId, runId);
            return captureLimitRepository.findByProjectIdAndDerivationRunIdOrderByCapturedAtDesc(projectId, runId);
        }
        if (reason != null) {
            return captureLimitRepository.findByProjectIdAndReasonOrderByCapturedAtDesc(projectId, reason);
        }
        return captureLimitRepository.findByProjectIdOrderByCapturedAtDesc(projectId);
    }

    private DerivationRun findRunOrThrow(UUID projectId, UUID runId) {
        return runRepository
                .findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new NotFoundException("Derivation run not found: " + runId));
    }

    private DerivationScope normalizeScope(CreateDerivationRunCommand command) {
        if (command.scopeMode() == null) {
            throw validation("scopeMode is required", "scopeMode");
        }
        var commitSha = normalizeCommitSha(command.commitSha(), "commitSha");
        var baseCommitSha =
                command.baseCommitSha() == null || command.baseCommitSha().isBlank()
                        ? null
                        : normalizeCommitSha(command.baseCommitSha(), "baseCommitSha");
        var paths = normalizePaths(command.paths(), command.scopeMode());
        var languages = normalizeTokens(command.languages(), "languages");
        var surfaces = normalizeTokens(command.surfaces(), "surfaces");
        if (command.scopeMode() == DerivationScopeMode.DIFF && baseCommitSha == null) {
            throw validation("baseCommitSha is required for DIFF scope", "baseCommitSha");
        }
        return new DerivationScope(command.scopeMode(), commitSha, baseCommitSha, paths, languages, surfaces);
    }

    private String normalizeCommitSha(String value, String field) {
        if (value == null
                || value.isBlank()
                || !COMMIT_SHA.matcher(value.trim()).matches()) {
            throw validation(field + " must be a 7-64 character hexadecimal commit SHA", field);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normalizePaths(List<String> input, DerivationScopeMode mode) {
        var paths = input == null ? List.<String>of() : input;
        if (paths.size() > MAX_PATHS) {
            throw validation("paths must contain at most " + MAX_PATHS + " entries", PATHS_FIELD);
        }
        if (mode == DerivationScopeMode.PATH_SET && paths.isEmpty()) {
            throw validation("paths is required for PATH_SET scope", PATHS_FIELD);
        }
        if (mode == DerivationScopeMode.FULL_REPO && !paths.isEmpty()) {
            throw validation("paths must be empty for FULL_REPO scope", PATHS_FIELD);
        }
        var normalized = new ArrayList<String>(paths.size());
        for (String path : paths) {
            normalized.add(normalizePath(path));
        }
        return List.copyOf(normalized);
    }

    private String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            throw validation("paths must not contain blank entries", PATHS_FIELD);
        }
        var path = value.trim().replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw validation("paths must be relative repository paths", PATHS_FIELD);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        int segmentStart = 0;
        while (segmentStart <= path.length()) {
            var slashIndex = path.indexOf('/', segmentStart);
            var segmentEnd = slashIndex == -1 ? path.length() : slashIndex;
            var segment = path.substring(segmentStart, segmentEnd);
            if (segment.isBlank() || segment.equals("..")) {
                throw validation("paths must not contain empty or parent-directory segments", PATHS_FIELD);
            }
            if (slashIndex == -1) {
                break;
            }
            segmentStart = slashIndex + 1;
        }
        return path;
    }

    private Set<String> normalizeTokens(List<String> input, String field) {
        if (input == null || input.isEmpty()) {
            throw validation(field + " must contain at least one entry", field);
        }
        if (input.size() > MAX_SCOPE_VALUES) {
            throw validation(field + " must contain at most " + MAX_SCOPE_VALUES + " entries", field);
        }
        var values = new java.util.LinkedHashSet<String>();
        for (String value : input) {
            if (value == null || value.isBlank()) {
                throw validation(field + " must not contain blank entries", field);
            }
            var normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!SCOPE_TOKEN.matcher(normalized).matches()) {
                throw validation(field + " entries must be lowercase language/surface tokens", field);
            }
            values.add(normalized);
        }
        return Set.copyOf(values);
    }

    private DerivedSystemModelFact validateFact(DerivedSystemModelFact fact, DerivationScope scope) {
        if (fact == null || fact.factKind() == null || isBlank(fact.factKey()) || isBlank(fact.label())) {
            throw validation("Derivation adapters must return facts with kind, factKey, and label", "facts");
        }
        if (fact.provenance() == null) {
            throw validation("Derivation facts must include provenance", "facts.provenance");
        }
        var provenance = fact.provenance();
        if (isBlank(provenance.adapterId())
                || isBlank(provenance.toolName())
                || isBlank(provenance.toolVersion())
                || isBlank(provenance.rulesetName())
                || isBlank(provenance.rulesetVersion())
                || isBlank(provenance.commitSha())
                || provenance.derivedAt() == null) {
            throw validation("Derivation fact provenance is incomplete", "facts.provenance");
        }
        if (!scope.commitSha().equalsIgnoreCase(provenance.commitSha())) {
            throw validation(
                    "Derivation fact provenance commitSha must match the requested scope",
                    "facts.provenance.commitSha");
        }
        rejectSensitivePayloadKeys(fact.payload(), "facts.payload");
        return fact;
    }

    private DerivationCaptureLimitDraft validateCaptureLimit(DerivationCaptureLimitDraft limit, DerivationScope scope) {
        if (limit == null || limit.reason() == null || isBlank(limit.language()) || isBlank(limit.surface())) {
            throw validation("Capture limits must include reason, language, and surface", "captureLimits");
        }
        if (isBlank(limit.commitSha()) || !scope.commitSha().equalsIgnoreCase(limit.commitSha())) {
            throw validation("Capture limit commitSha must match the requested scope", "captureLimits.commitSha");
        }
        if (limit.capturedAt() == null) {
            throw validation("Capture limits must include capturedAt", "captureLimits.capturedAt");
        }
        return limit;
    }

    @SuppressWarnings("unchecked")
    private void rejectSensitivePayloadKeys(Map<String, Object> payload, String path) {
        if (payload == null) {
            return;
        }
        for (var entry : payload.entrySet()) {
            var key = entry.getKey() == null
                    ? ""
                    : entry.getKey().replace("-", "_").toLowerCase(Locale.ROOT);
            if (BLOCKED_PAYLOAD_KEYS.contains(key)) {
                throw validation(
                        "Derivation fact payload contains a blocked raw-content field", path + "." + entry.getKey());
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                rejectSensitivePayloadKeys((Map<String, Object>) nested, path + "." + entry.getKey());
            }
        }
    }

    private static String first(Set<String> values) {
        return values.stream().findFirst().orElse("unknown");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private DomainValidationException validation(String message, String field) {
        Map<String, Serializable> detail = new LinkedHashMap<>();
        detail.put("field", field);
        return new DomainValidationException(message, "validation_error", detail);
    }
}
