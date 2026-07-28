package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.CONTRACT_ENTRY_KEY_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.CONTRACT_SCHEMA_VERSION;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.ENTRY_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.INVALID_CODE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.LOCATOR_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.LOCATOR_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.METHOD_KEY_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.METHOD_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.NO_ACTIVE_METHODOLOGY_SELECTION;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.PROFILE_VERSION_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.REFERENCES_ENTRY_KEY_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SOURCE_ID_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.STATEMENT_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.currentActor;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.emptyToNull;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.key;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.log;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireActive;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.requireUnder;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntry;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntrySourceLink;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractRejectedAlternative;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntrySourceLinkRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRejectedAlternativeRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySelectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySourceRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRationaleEntryRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records and reads the methodology requirements contract (ADR-080).
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunContractOperations {

    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunRationaleEntryRepository rationaleRepository;
    private final ResearchRunMethodologySelectionRepository methodologySelectionRepository;
    private final ResearchRunMethodologySourceRepository methodologySourceRepository;
    private final MethodologyCatalog methodologyCatalog;
    private final MethodologyRequirementsContractRepository contractRepository;
    private final MethodologyRequirementsContractEntryRepository contractEntryRepository;
    private final MethodologyRequirementsContractEntrySourceLinkRepository contractEntrySourceLinkRepository;
    private final MethodologyRequirementsContractRejectedAlternativeRepository contractRejectedAlternativeRepository;
    private final ResearchRunService service;

    ResearchRunContractOperations(
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunRationaleEntryRepository rationaleRepository,
            ResearchRunMethodologySelectionRepository methodologySelectionRepository,
            ResearchRunMethodologySourceRepository methodologySourceRepository,
            MethodologyCatalog methodologyCatalog,
            MethodologyRequirementsContractRepository contractRepository,
            MethodologyRequirementsContractEntryRepository contractEntryRepository,
            MethodologyRequirementsContractEntrySourceLinkRepository contractEntrySourceLinkRepository,
            MethodologyRequirementsContractRejectedAlternativeRepository contractRejectedAlternativeRepository,
            ResearchRunService service) {
        this.artifactRepository = artifactRepository;
        this.rationaleRepository = rationaleRepository;
        this.methodologySelectionRepository = methodologySelectionRepository;
        this.methodologySourceRepository = methodologySourceRepository;
        this.methodologyCatalog = methodologyCatalog;
        this.contractRepository = contractRepository;
        this.contractEntryRepository = contractEntryRepository;
        this.contractEntrySourceLinkRepository = contractEntrySourceLinkRepository;
        this.contractRejectedAlternativeRepository = contractRejectedAlternativeRepository;
        this.service = service;
    }

    // ------------------------------------------------------------------
    // Methodology requirements contract (GC-RSCH-F007 / ADR-080)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F007 / GC-RSCH-R002 / ADR-080 — record the structured phase-1
     * methodology requirements contract behind the run's ACTIVE {@code
     * METHODOLOGY_REQUIREMENTS} artifact attempt. The chosen method (active
     * selection), artifact id, and attempt are resolved server-side. Exactly one
     * contract exists per artifact attempt; a rework records a new artifact
     * attempt first.
     *
     * <p>Every {@code REQUIREMENT} / {@code METHOD_LIMIT} / {@code NON_CLAIM}
     * entry must link at least one methodology source that belongs to the active
     * selection and is {@code READ} — a claim with no READ source link is never
     * accepted (no model memory as scientific evidence). An {@code
     * OPEN_PROTOCOL_QUESTION} may instead reference another entry in the same
     * contract. Rejected alternatives may point at a {@code METHODOLOGY_CHOICE}
     * rationale entry for the same run.
     */
    MethodologyRequirementsContractAggregate recordMethodologyRequirementsContract(
            UUID projectId, UUID runId, RecordMethodologyRequirementsContractCommand command) {
        var run = service.requireRun(projectId, runId);
        requireActive(run);
        if (command == null) {
            throw new DomainValidationException("Contract command must not be null", INVALID_CODE, Map.of());
        }

        // The contract sits behind the ACTIVE METHODOLOGY_REQUIREMENTS artifact.
        var artifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new DomainValidationException(
                        "No ACTIVE METHODOLOGY_REQUIREMENTS artifact exists for this run; record the artifact first",
                        "research_run_methodology_artifact_missing",
                        Map.of()));

        // Required methodology sources must be READ before the contract is accepted
        // (ADR-080 §3) — the same gate the artifact recording enforced.
        service.requireMethodologySourceCoverageComplete(runId);
        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        // One contract per artifact attempt.
        if (contractRepository.existsByArtifactId(artifact.getId())) {
            throw new ConflictException(
                    "A methodology requirements contract already exists for this artifact attempt",
                    "research_run_methodology_contract_exists",
                    Map.of("artifact_id", artifact.getId().toString()));
        }

        var entryCommands = command.entries();
        if (entryCommands == null || entryCommands.isEmpty()) {
            throw new DomainValidationException(
                    "A methodology requirements contract must have at least one entry",
                    INVALID_CODE,
                    Map.of(FIELD, "entries"));
        }

        // Sources of the active selection, keyed by id, for membership + READ checks.
        var selectionSources = new HashMap<UUID, ResearchRunMethodologySource>();
        for (var s : methodologySourceRepository.findBySelectionId(selection.getId())) {
            selectionSources.put(s.getId(), s);
        }

        var kindByKey = validateContractEntryShape(entryCommands);
        validateContractEntryGrounding(entryCommands, kindByKey, selectionSources);
        var rejectedCommands = command.rejectedAlternatives();
        validateRejectedAlternatives(rejectedCommands, runId);

        return persistContract(run, selection, artifact, entryCommands, rejectedCommands, selectionSources);
    }

    /**
     * First pass over the entries: validates shape (kind/entryKey/statement) and
     * returns the kind-by-key map needed to resolve {@code OPEN_PROTOCOL_QUESTION}
     * references in {@link #validateContractEntryGrounding}.
     */
    private Map<String, ContractEntryKind> validateContractEntryShape(
            List<RecordMethodologyRequirementsContractCommand.EntryCommand> entryCommands) {
        var entryKeys = new HashSet<String>();
        var kindByKey = new HashMap<String, ContractEntryKind>();
        for (var e : entryCommands) {
            if (e == null || e.kind() == null) {
                throw new DomainValidationException("entry kind must not be null", INVALID_CODE, Map.of(FIELD, "kind"));
            }
            var key = emptyToNull(e.entryKey());
            if (key == null) {
                throw new DomainValidationException(
                        "entryKey must not be blank", INVALID_CODE, Map.of(FIELD, CONTRACT_ENTRY_KEY_FIELD));
            }
            requireUnder(key, ENTRY_KEY_MAX, CONTRACT_ENTRY_KEY_FIELD);
            if (!entryKeys.add(key)) {
                throw new DomainValidationException(
                        "Duplicate entryKey in contract: " + key,
                        "research_run_methodology_contract_duplicate_entry_key",
                        Map.of(CONTRACT_ENTRY_KEY_FIELD, key));
            }
            kindByKey.put(key, e.kind());
            if (emptyToNull(e.statement()) == null) {
                throw new DomainValidationException(
                        "statement must not be blank", INVALID_CODE, Map.of(FIELD, "statement"));
            }
            requireUnder(e.statement(), STATEMENT_MAX, "statement");
        }
        return kindByKey;
    }

    /** Second pass over the entries: validates grounding (source links / references). */
    private void validateContractEntryGrounding(
            List<RecordMethodologyRequirementsContractCommand.EntryCommand> entryCommands,
            Map<String, ContractEntryKind> kindByKey,
            Map<UUID, ResearchRunMethodologySource> selectionSources) {
        for (var e : entryCommands) {
            var links = e.sourceLinks();
            var hasLinks = links != null && !links.isEmpty();
            var reference = emptyToNull(e.referencesEntryKey());
            requireEntryGroundingPresent(e, hasLinks, reference);
            if (reference != null) {
                validateEntryReference(e, reference, kindByKey);
            }
            if (hasLinks) {
                validateEntrySourceLinks(e, links, selectionSources);
            }
        }
    }

    private void requireEntryGroundingPresent(
            RecordMethodologyRequirementsContractCommand.EntryCommand e, boolean hasLinks, String reference) {
        if (e.kind().requiresSourceGrounding()) {
            if (!hasLinks) {
                throw new DomainValidationException(
                        e.kind() + " entry '" + key(e) + "' must link at least one methodology source",
                        "research_run_methodology_contract_entry_ungrounded",
                        Map.of(
                                CONTRACT_ENTRY_KEY_FIELD,
                                key(e),
                                "kind",
                                e.kind().name()));
            }
        } else if (!hasLinks && reference == null) {
            throw new DomainValidationException(
                    "OPEN_PROTOCOL_QUESTION entry '" + key(e) + "' must link a source or reference another entry",
                    "research_run_methodology_contract_open_question_unlinked",
                    Map.of(CONTRACT_ENTRY_KEY_FIELD, key(e)));
        }
    }

    private void validateEntryReference(
            RecordMethodologyRequirementsContractCommand.EntryCommand e,
            String reference,
            Map<String, ContractEntryKind> kindByKey) {
        if (reference.equals(key(e))) {
            throw new DomainValidationException(
                    "entry '" + key(e) + "' may not reference itself",
                    "research_run_methodology_contract_self_reference",
                    Map.of(REFERENCES_ENTRY_KEY_FIELD, reference));
        }
        var referencedKind = kindByKey.get(reference);
        if (referencedKind == null) {
            throw new DomainValidationException(
                    "referencesEntryKey '" + reference + "' does not match any entry in this contract",
                    "research_run_methodology_contract_bad_reference",
                    Map.of(REFERENCES_ENTRY_KEY_FIELD, reference));
        }
        // ADR-080 §3: a reference must resolve to a source-grounded entry
        // (REQUIREMENT / METHOD_LIMIT / NON_CLAIM), never to another
        // OPEN_PROTOCOL_QUESTION — otherwise an unlinked question could chain
        // to another question and enter phase 2 with no source grounding.
        if (!referencedKind.requiresSourceGrounding()) {
            throw new DomainValidationException(
                    "referencesEntryKey '" + reference
                            + "' must target a source-grounded entry (REQUIREMENT, METHOD_LIMIT, or NON_CLAIM)",
                    "research_run_methodology_contract_reference_not_grounded",
                    Map.of(REFERENCES_ENTRY_KEY_FIELD, reference, "referenced_kind", referencedKind.name()));
        }
    }

    private void validateEntrySourceLinks(
            RecordMethodologyRequirementsContractCommand.EntryCommand e,
            List<RecordMethodologyRequirementsContractCommand.SourceLinkCommand> links,
            Map<UUID, ResearchRunMethodologySource> selectionSources) {
        var seenSources = new HashSet<UUID>();
        for (var link : links) {
            if (link == null || link.sourceId() == null) {
                throw new DomainValidationException(
                        "source link sourceId must not be null", INVALID_CODE, Map.of(FIELD, "sourceId"));
            }
            if (!seenSources.add(link.sourceId())) {
                throw new DomainValidationException(
                        "Duplicate source link within entry '" + key(e) + "'",
                        "research_run_methodology_contract_duplicate_source_link",
                        Map.of(SOURCE_ID_FIELD, link.sourceId().toString()));
            }
            var source = selectionSources.get(link.sourceId());
            if (source == null) {
                throw new DomainValidationException(
                        "Source link target is not a source of the active methodology selection",
                        "research_run_methodology_contract_source_not_in_selection",
                        Map.of(SOURCE_ID_FIELD, link.sourceId().toString()));
            }
            if (source.getState() != MethodologySourceState.READ) {
                throw new DomainValidationException(
                        "Source link target must be READ before it can ground a contract entry",
                        "research_run_methodology_contract_source_not_read",
                        Map.of(
                                SOURCE_ID_FIELD,
                                link.sourceId().toString(),
                                "state",
                                source.getState().name()));
            }
            requireUnder(link.locator(), LOCATOR_MAX, LOCATOR_FIELD);
        }
    }

    /** Validates rejected alternatives: shape, catalog membership, and rationale linkage. */
    private void validateRejectedAlternatives(
            List<RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand> rejectedCommands,
            UUID runId) {
        if (rejectedCommands == null) {
            return;
        }
        for (var r : rejectedCommands) {
            if (r == null || emptyToNull(r.methodKey()) == null) {
                throw new DomainValidationException(
                        "rejected alternative methodKey must not be blank",
                        INVALID_CODE,
                        Map.of(FIELD, METHOD_KEY_FIELD));
            }
            requireUnder(r.methodKey(), METHOD_KEY_MAX, METHOD_KEY_FIELD);
            requireUnder(r.profileVersion(), PROFILE_VERSION_MAX, "profileVersion");
            // ADR-080 §2: a non-external rejected alternative claims a catalog
            // method and must resolve against the backend MethodologyCatalog. An
            // unknown method must instead be recorded through the external/manual path.
            if (!r.external()
                    && methodologyCatalog.findProfile(r.methodKey().trim()).isEmpty()) {
                throw new DomainValidationException(
                        "rejected alternative method '" + r.methodKey().trim()
                                + "' is not in the methodology catalog; unknown methods must be recorded as external",
                        "research_run_methodology_contract_rejected_alternative_unknown_method",
                        Map.of("method_key", r.methodKey().trim()));
            }
            if (r.rationaleEntryId() != null) {
                requireRejectedAlternativeRationale(r, runId);
            }
        }
    }

    private void requireRejectedAlternativeRationale(
            RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand r, UUID runId) {
        var rationale = rationaleRepository
                .findById(r.rationaleEntryId())
                .filter(entry -> entry.getResearchRun().getId().equals(runId))
                .orElseThrow(() -> new DomainValidationException(
                        "rejected alternative rationale entry not found for this run",
                        "research_run_methodology_contract_rationale_not_found",
                        Map.of("rationale_entry_id", r.rationaleEntryId().toString())));
        if (rationale.getKind() != RationaleEntryKind.METHODOLOGY_CHOICE) {
            throw new DomainValidationException(
                    "rejected alternative rationale entry must be of kind METHODOLOGY_CHOICE",
                    "research_run_methodology_contract_rationale_wrong_kind",
                    Map.of("kind", rationale.getKind().name()));
        }
    }

    /** Persists the contract aggregate (contract, entries, source links, rejected alternatives). */
    private MethodologyRequirementsContractAggregate persistContract(
            ResearchRun run,
            ResearchRunMethodologySelection selection,
            ResearchRunArtifact artifact,
            List<RecordMethodologyRequirementsContractCommand.EntryCommand> entryCommands,
            List<RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand> rejectedCommands,
            Map<UUID, ResearchRunMethodologySource> selectionSources) {
        var actor = currentActor();
        var contract = new MethodologyRequirementsContract(
                run, selection, artifact.getId(), artifact.getAttemptNo(), CONTRACT_SCHEMA_VERSION, actor);
        var savedContract = contractRepository.save(contract);

        var savedEntries = new ArrayList<MethodologyRequirementsContractEntry>();
        var savedLinks = new ArrayList<MethodologyRequirementsContractEntrySourceLink>();
        for (var e : entryCommands) {
            var entry = new MethodologyRequirementsContractEntry(
                    savedContract, e.kind(), key(e), e.statement().trim(), emptyToNull(e.referencesEntryKey()), actor);
            var savedEntry = contractEntryRepository.save(entry);
            savedEntries.add(savedEntry);
            if (e.sourceLinks() != null) {
                for (var link : e.sourceLinks()) {
                    var source = selectionSources.get(link.sourceId());
                    savedLinks.add(
                            contractEntrySourceLinkRepository.save(new MethodologyRequirementsContractEntrySourceLink(
                                    savedEntry, source, emptyToNull(link.locator()))));
                }
            }
        }

        var savedRejected = new ArrayList<MethodologyRequirementsContractRejectedAlternative>();
        if (rejectedCommands != null) {
            for (var r : rejectedCommands) {
                savedRejected.add(contractRejectedAlternativeRepository.save(
                        new MethodologyRequirementsContractRejectedAlternative(
                                savedContract,
                                r.rationaleEntryId(),
                                r.methodKey().trim(),
                                emptyToNull(r.profileVersion()),
                                r.external())));
            }
        }

        log.info(
                "research_run_methodology_contract_recorded: project={} run={} artifact={} attempt={} entries={} links={} rejected={}",
                run.getProject().getIdentifier(),
                run.getId(),
                artifact.getId(),
                artifact.getAttemptNo(),
                savedEntries.size(),
                savedLinks.size(),
                savedRejected.size());

        return new MethodologyRequirementsContractAggregate(savedContract, savedEntries, savedLinks, savedRejected);
    }

    /**
     * GC-RSCH-F008 / ADR-080 §5 — read the active methodology requirements
     * contract (the surface protocol planning consumes as its contract). Resolves
     * the ACTIVE {@code METHODOLOGY_REQUIREMENTS} artifact, then the contract tied
     * to that attempt, and bundles its entries, source links, and rejected
     * alternatives. {@link NotFoundException} when no contract has been recorded.
     */
    MethodologyRequirementsContractAggregate getMethodologyRequirementsContract(UUID projectId, UUID runId) {
        service.requireRun(projectId, runId);
        var artifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No methodology requirements contract for run " + runId));
        var contract = contractRepository
                .findByArtifactId(artifact.getId())
                .orElseThrow(() -> new NotFoundException("No methodology requirements contract for run " + runId));
        var entries = contractEntryRepository.findByContractIdOrderByCreatedAtAsc(contract.getId());
        var links = contractEntrySourceLinkRepository.findByEntryContractIdOrderByCreatedAtAsc(contract.getId());
        var rejected = contractRejectedAlternativeRepository.findByContractIdOrderByCreatedAtAsc(contract.getId());
        return new MethodologyRequirementsContractAggregate(contract, entries, links, rejected);
    }
}
