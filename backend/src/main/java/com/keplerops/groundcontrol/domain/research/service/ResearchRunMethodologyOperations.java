package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.INVALID_CODE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.METHOD_KEY_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.METHOD_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.NO_ACTIVE_METHODOLOGY_SELECTION;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SOURCE_LABEL_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SOURCE_REF_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.currentActor;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.emptyToNull;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.log;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireActive;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireUnder;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySelectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySourceRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Methodology selection and source coverage (GC-RSCH-F006).
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunMethodologyOperations {

    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunMethodologySelectionRepository methodologySelectionRepository;
    private final ResearchRunMethodologySourceRepository methodologySourceRepository;
    private final MethodologyCatalog methodologyCatalog;
    private final ResearchRunService service;

    ResearchRunMethodologyOperations(
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunMethodologySelectionRepository methodologySelectionRepository,
            ResearchRunMethodologySourceRepository methodologySourceRepository,
            MethodologyCatalog methodologyCatalog,
            ResearchRunService service) {
        this.service = service;
        this.artifactRepository = artifactRepository;
        this.methodologySelectionRepository = methodologySelectionRepository;
        this.methodologySourceRepository = methodologySourceRepository;
        this.methodologyCatalog = methodologyCatalog;
    }

    // ------------------------------------------------------------------
    // Methodology selection + source coverage (GC-RSCH-F006)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F006 / ADR-078 — select (or re-select) the active methodology for a
     * run. The selected {@code methodKey} is resolved against the backend-owned
     * methodology catalog; the label, profile/catalog version, and required
     * primary-source set are all DERIVED from the catalog profile (never supplied
     * by the caller). Each required source is snapshotted as an immutable
     * {@code required=true} row in {@code ATTEMPTED} state.
     *
     * <p>Idempotent when the same method is re-selected and the snapshotted
     * required sources still match the catalog profile (no catalog drift): the
     * existing selection is returned unchanged, preserving any recorded source
     * progress. Selecting a different method (or a profile whose required-source
     * set has since changed) supersedes the prior active selection and re-snapshots.
     */
    ResearchRunMethodologySelection selectMethodology(UUID projectId, UUID runId, SelectMethodologyCommand cmd) {
        var run = service.requireRun(projectId, runId);
        requireActive(run);
        if (cmd == null || cmd.methodKey() == null || cmd.methodKey().isBlank()) {
            throw new DomainValidationException(
                    "methodKey must not be blank", INVALID_CODE, Map.of(FIELD, METHOD_KEY_FIELD));
        }
        var methodKey = cmd.methodKey().trim();
        requireUnder(methodKey, METHOD_KEY_MAX, METHOD_KEY_FIELD);
        var profile = methodologyCatalog.requireProfile(methodKey);

        var existing = methodologySelectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(runId);
        if (existing.isPresent()) {
            var sel = existing.get();
            // Idempotent re-select: same method+versions and the snapshotted
            // required source refs still match the catalog profile → return existing
            // unchanged (does not re-open or discard recorded source progress).
            var sameTuple = Objects.equals(sel.getMethodKey(), profile.methodKey())
                    && Objects.equals(sel.getProfileVersion(), profile.profileVersion())
                    && Objects.equals(sel.getCatalogVersion(), profile.catalogVersion());
            if (sameTuple) {
                var existingSources = methodologySourceRepository.findBySelectionId(sel.getId());
                if (requiredRefsMatchProfile(existingSources, profile)) {
                    return sel;
                }
            }
            // Superseding the active selection re-snapshots a fresh (unread) required
            // set, which would leave an already-accepted METHODOLOGY_REQUIREMENTS
            // artifact's coverage unsatisfied by the new selection. Methodology is
            // therefore locked once its requirements artifact is recorded — reselection
            // is rejected rather than silently invalidating accepted downstream state.
            if (artifactRepository
                    .findByResearchRunIdAndArtifactTypeAndStatus(
                            runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                    .isPresent()) {
                throw new ConflictException(
                        "Methodology cannot be changed after the METHODOLOGY_REQUIREMENTS artifact has been recorded",
                        "research_run_methodology_locked_after_requirements",
                        Map.of("method_key", sel.getMethodKey()));
            }
            // Supersede prior selection before creating the new one.
            sel.supersede();
            methodologySelectionRepository.save(sel);
        }

        var actor = currentActor();
        var selection = new ResearchRunMethodologySelection(run, profile.methodKey(), actor);
        selection.setMethodLabel(profile.label());
        selection.setProfileVersion(profile.profileVersion());
        selection.setCatalogVersion(profile.catalogVersion());
        var saved = methodologySelectionRepository.save(selection);

        // Snapshot the catalog profile's required sources as immutable required=true
        // rows on the new selection. The required-source set is derived from the
        // selected method+version, not from the request.
        for (var source : profile.requiredSources()) {
            var row = new ResearchRunMethodologySource(saved, source.ref(), true, actor);
            row.setSourceLabel(source.title());
            methodologySourceRepository.save(row);
        }

        log.info(
                "research_run_methodology_selected: project={} run={} selection={} methodKey={} version={} requiredRefs={}",
                run.getProject().getIdentifier(),
                runId,
                saved.getId(),
                profile.methodKey(),
                profile.profileVersion(),
                profile.requiredSources().size());
        return saved;
    }

    /**
     * True when the required source refs already snapshotted on an active selection
     * exactly match the catalog profile's required-source set. Used to decide
     * whether re-selecting the same method is idempotent or must re-snapshot after
     * the catalog's required-source set changed.
     */
    private boolean requiredRefsMatchProfile(
            List<ResearchRunMethodologySource> existingSources,
            com.keplerops.groundcontrol.domain.research.model.MethodProfile profile) {
        var existingRequiredRefs = existingSources.stream()
                .filter(ResearchRunMethodologySource::isRequired)
                .map(ResearchRunMethodologySource::getSourceRef)
                .sorted()
                .toList();
        var profileRefs = profile.requiredSources().stream()
                .map(com.keplerops.groundcontrol.domain.research.model.MethodProfileSource::ref)
                .sorted()
                .toList();
        return existingRequiredRefs.equals(profileRefs);
    }

    /**
     * GC-RSCH-F006 — record a methodology source on the active selection.
     * Idempotent on sourceRef: if a source with the same ref already exists in
     * the active selection, the existing record is returned unchanged.
     */
    ResearchRunMethodologySource recordMethodologySource(
            UUID projectId, UUID runId, RecordMethodologySourceCommand cmd) {
        var run = service.requireRun(projectId, runId);
        requireActive(run);
        if (cmd == null || cmd.sourceRef() == null || cmd.sourceRef().isBlank()) {
            throw new DomainValidationException(
                    "sourceRef must not be blank", INVALID_CODE, Map.of(FIELD, "sourceRef"));
        }
        var sourceRef = cmd.sourceRef().trim();
        requireUnder(sourceRef, SOURCE_REF_MAX, "sourceRef");
        requireUnder(cmd.sourceLabel(), SOURCE_LABEL_MAX, "sourceLabel");

        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        // Idempotent: same sourceRef in this selection → return existing.
        var existing = methodologySourceRepository.findBySelectionIdAndSourceRef(selection.getId(), sourceRef);
        if (existing.isPresent()) {
            return existing.get();
        }

        var actor = currentActor();
        // Sources recorded via this method are always optional (required=false).
        // Required sources are derived from the selected method's catalog profile
        // and snapshotted at selection (ADR-078), not recorded here.
        var source = new ResearchRunMethodologySource(selection, sourceRef, false, actor);
        source.setSourceLabel(emptyToNull(cmd.sourceLabel()));
        var saved = methodologySourceRepository.save(source);
        log.info(
                "research_run_methodology_source_recorded: project={} run={} source={} required=false",
                run.getProject().getIdentifier(),
                runId,
                saved.getId());
        return saved;
    }

    /**
     * GC-RSCH-F006 — update the state of a methodology source. Idempotent:
     * if already in the target state, the existing record is returned unchanged.
     */
    ResearchRunMethodologySource updateMethodologySourceState(
            UUID projectId, UUID runId, UUID sourceId, UpdateMethodologySourceStateCommand cmd) {
        var run = service.requireRun(projectId, runId);
        requireActive(run);
        if (cmd == null || cmd.state() == null) {
            throw new DomainValidationException("state must not be null", INVALID_CODE, Map.of(FIELD, "state"));
        }
        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        var sources = methodologySourceRepository.findBySelectionId(selection.getId());
        var source = sources.stream()
                .filter(s -> s.getId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Methodology source not found: " + sourceId));

        if (source.getState() == cmd.state()) {
            return source; // already in target state — idempotent
        }
        if (!source.getState().canTransitionTo(cmd.state())) {
            throw new ConflictException(
                    "Invalid state transition for methodology source: " + source.getState() + " → " + cmd.state(),
                    "research_run_methodology_source_invalid_transition",
                    Map.of(
                            "from", source.getState().name(),
                            "to", cmd.state().name(),
                            "source_ref", source.getSourceRef()));
        }
        source.setState(cmd.state());
        var saved = methodologySourceRepository.save(source);
        log.info(
                "research_run_methodology_source_state_updated: run={} source={} state={}",
                runId,
                sourceId,
                cmd.state());
        return saved;
    }

    /**
     * GC-RSCH-F006 / ADR-078 — the backend-owned methodology catalog: all method
     * profiles with their required primary sources. Global reference data, not
     * project- or run-scoped.
     */
    List<com.keplerops.groundcontrol.domain.research.model.MethodProfile> listMethodologyCatalog() {
        return methodologyCatalog.allProfiles();
    }

    /** GC-RSCH-F006 — get the active methodology selection for a run. */
    ResearchRunMethodologySelection getMethodologySelection(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        return methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));
    }

    /** GC-RSCH-F006 — list all sources for the active methodology selection (empty if none). */
    List<ResearchRunMethodologySource> listMethodologySources(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        var selection = methodologySelectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(runId);
        return selection
                .map(s -> methodologySourceRepository.findBySelectionId(s.getId()))
                .orElse(List.of());
    }
    /**
     * GC-RSCH-F006 — enforce that all required methodology sources are in READ
     * state before the METHODOLOGY_REQUIREMENTS artifact can be recorded.
     * <ul>
     *   <li>No active selection → {@link DomainValidationException} with code
     *       {@code research_run_methodology_selection_missing}.</li>
     *   <li>A required source in {@code BLOCKED} state → {@link ConflictException}
     *       with code {@code research_run_methodology_source_blocked}.</li>
     *   <li>Any required source not in {@code READ} state → {@link DomainValidationException}
     *       with code {@code research_run_methodology_sources_incomplete}.</li>
     * </ul>
     */
    void requireMethodologySourceCoverageComplete(UUID runId) {
        var selection = methodologySelectionRepository.findFirstByResearchRunIdAndSupersededAtIsNull(runId);
        if (selection.isEmpty()) {
            throw new DomainValidationException(
                    "A methodology selection is required before recording a METHODOLOGY_REQUIREMENTS artifact",
                    "research_run_methodology_selection_missing",
                    Map.of());
        }
        var sources =
                methodologySourceRepository.findBySelectionId(selection.get().getId());
        var requiredSources = sources.stream()
                .filter(ResearchRunMethodologySource::isRequired)
                .toList();

        // Check for BLOCKED required sources first — these are a distinct conflict.
        var blockedSource = requiredSources.stream()
                .filter(s -> s.getState() == MethodologySourceState.BLOCKED)
                .findFirst();
        if (blockedSource.isPresent()) {
            throw new ConflictException(
                    "Required methodology source is BLOCKED: "
                            + blockedSource.get().getSourceRef(),
                    "research_run_methodology_source_blocked",
                    Map.of("blocked_source_ref", blockedSource.get().getSourceRef()));
        }

        // Any required source not READ blocks the gate.
        var notReadSources = requiredSources.stream()
                .filter(s -> s.getState() != MethodologySourceState.READ)
                .toList();
        if (!notReadSources.isEmpty()) {
            throw new DomainValidationException(
                    "All required methodology sources must be in READ state before recording a METHODOLOGY_REQUIREMENTS artifact",
                    "research_run_methodology_sources_incomplete",
                    Map.of(
                            "blocked_sources",
                            String.valueOf(notReadSources.size()),
                            "first_blocked_ref",
                            notReadSources.get(0).getSourceRef()));
        }
    }
}
