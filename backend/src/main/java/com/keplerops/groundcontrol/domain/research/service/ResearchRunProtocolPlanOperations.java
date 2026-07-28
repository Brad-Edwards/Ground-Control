package com.keplerops.groundcontrol.domain.research.service;

import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.ANSWER_SUMMARY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.CONTRACT_ENTRY_KEY_JSON_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.DECISION_REFERENCE_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.DISPOSITION_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.ENTRY_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.INVALID_CODE;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.METHOD_KEY_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.NO_ACTIVE_METHODOLOGY_SELECTION;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.PROTOCOL_RATIONALE_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.PROTOCOL_SCHEMA_VERSION_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SECTION_KEY_JSON_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SECTION_KEY_MAX;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SECTION_KIND_FIELD;
import static com.keplerops.groundcontrol.domain.research.service.ResearchRunService.SUMMARY_MAX;
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
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlan;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanCoverage;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanSection;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSourceRole;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository;
import com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySelectionRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records and validates a research run's protocol plan (ADR-083).
 *
 * Split out of {@link ResearchRunService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class ResearchRunProtocolPlanOperations {

    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchRunMethodologySelectionRepository methodologySelectionRepository;
    private final MethodologyRequirementsContractRepository contractRepository;
    private final MethodologyRequirementsContractEntryRepository contractEntryRepository;
    private final ProtocolPlanRepository protocolPlanRepository;
    private final ProtocolPlanCoverageRepository protocolPlanCoverageRepository;
    private final ProtocolPlanSectionRepository protocolPlanSectionRepository;
    private final ResearchRunService service;

    @SuppressWarnings("java:S107") // aggregates the run repositories from one place on purpose
    ResearchRunProtocolPlanOperations(
            ResearchRunArtifactRepository artifactRepository,
            ResearchRunMethodologySelectionRepository methodologySelectionRepository,
            MethodologyRequirementsContractRepository contractRepository,
            MethodologyRequirementsContractEntryRepository contractEntryRepository,
            ProtocolPlanRepository protocolPlanRepository,
            ProtocolPlanCoverageRepository protocolPlanCoverageRepository,
            ProtocolPlanSectionRepository protocolPlanSectionRepository,
            ResearchRunService service) {
        this.artifactRepository = artifactRepository;
        this.methodologySelectionRepository = methodologySelectionRepository;
        this.contractRepository = contractRepository;
        this.contractEntryRepository = contractEntryRepository;
        this.protocolPlanRepository = protocolPlanRepository;
        this.protocolPlanCoverageRepository = protocolPlanCoverageRepository;
        this.protocolPlanSectionRepository = protocolPlanSectionRepository;
        this.service = service;
    }

    // ------------------------------------------------------------------
    // Protocol plan (GC-RSCH-F008 / GC-RSCH-F009 / ADR-083)
    // ------------------------------------------------------------------

    /**
     * GC-RSCH-F008 / GC-RSCH-F009 / ADR-083 — record the structured protocol
     * plan behind the run's ACTIVE {@code PROTOCOL_PLAN} artifact attempt,
     * answering the run's one active ADR-080 methodology requirements contract.
     * Every current {@code REQUIREMENT} / {@code OPEN_PROTOCOL_QUESTION}
     * contract entry must have exactly one coverage disposition; {@code
     * METHOD_LIMIT} / {@code NON_CLAIM} entries are constraints the plan
     * carries forward, not coverable answers (ADR-083 §2). The plan must also
     * include every section kind the selected method profile requires ({@link
     * ProtocolMethodShape}); a source role may only be assigned on a {@code
     * SOURCE_ROLES} section of the taxonomy-development method (ADR-083 §3).
     */
    ProtocolPlanAggregate recordProtocolPlan(UUID projectId, UUID runId, RecordProtocolPlanCommand command) {
        var run = service.requireRun(projectId, runId);
        requireActive(run);
        if (command == null) {
            throw new DomainValidationException("Protocol plan command must not be null", INVALID_CODE, Map.of());
        }
        var schemaVersion = emptyToNull(command.protocolSchemaVersion());
        if (schemaVersion == null) {
            throw new DomainValidationException(
                    "protocolSchemaVersion must not be blank", INVALID_CODE, Map.of(FIELD, "protocolSchemaVersion"));
        }
        requireUnder(schemaVersion, PROTOCOL_SCHEMA_VERSION_MAX, "protocolSchemaVersion");

        // The plan sits behind the ACTIVE PROTOCOL_PLAN artifact.
        var artifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.PROTOCOL_PLAN, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new DomainValidationException(
                        "No ACTIVE PROTOCOL_PLAN artifact exists for this run; record the artifact first",
                        "research_run_protocol_plan_artifact_missing",
                        Map.of()));

        // One plan per artifact attempt.
        if (protocolPlanRepository.existsByArtifactId(artifact.getId())) {
            throw new ConflictException(
                    "A protocol plan already exists for this artifact attempt",
                    "research_run_protocol_plan_exists",
                    Map.of("artifact_id", artifact.getId().toString()));
        }

        // The plan answers the run's one active ADR-080 methodology requirements contract.
        var methodologyArtifact = artifactRepository
                .findByResearchRunIdAndArtifactTypeAndStatus(
                        runId, ResearchArtifactType.METHODOLOGY_REQUIREMENTS, ResearchArtifactStatus.ACTIVE)
                .orElseThrow(() -> new DomainValidationException(
                        "No ACTIVE METHODOLOGY_REQUIREMENTS artifact exists for this run",
                        "research_run_methodology_artifact_missing",
                        Map.of()));
        var contract = contractRepository
                .findByArtifactId(methodologyArtifact.getId())
                .orElseThrow(() -> new DomainValidationException(
                        "No methodology requirements contract has been recorded for this run; record it first",
                        "research_run_protocol_plan_contract_missing",
                        Map.of()));
        var contractEntries = contractEntryRepository.findByContractIdOrderByCreatedAtAsc(contract.getId());

        var selection = methodologySelectionRepository
                .findFirstByResearchRunIdAndSupersededAtIsNull(runId)
                .orElseThrow(() -> new NotFoundException(NO_ACTIVE_METHODOLOGY_SELECTION + runId));

        validateProtocolPlanCoverage(command.coverages(), contractEntries);
        validateProtocolPlanSections(command.sections(), selection.getMethodKey());

        return persistProtocolPlan(run, contract, artifact, selection, schemaVersion, command);
    }

    /**
     * First validation pass: every {@code REQUIREMENT} / {@code
     * OPEN_PROTOCOL_QUESTION} contract entry has exactly one coverage, no
     * unknown/duplicate {@code contractEntryKey} is present, and no coverage
     * targets a {@code METHOD_LIMIT} / {@code NON_CLAIM} entry.
     */
    private void validateProtocolPlanCoverage(
            List<RecordProtocolPlanCommand.CoverageCommand> coverageCommands,
            List<MethodologyRequirementsContractEntry> contractEntries) {
        var kindByKey = new HashMap<String, ContractEntryKind>();
        var coverableKeys = new HashSet<String>();
        for (var entry : contractEntries) {
            kindByKey.put(entry.getEntryKey(), entry.getKind());
            if (entry.getKind() == ContractEntryKind.REQUIREMENT
                    || entry.getKind() == ContractEntryKind.OPEN_PROTOCOL_QUESTION) {
                coverableKeys.add(entry.getEntryKey());
            }
        }
        var seenKeys = new HashSet<String>();
        var commands =
                coverageCommands == null ? List.<RecordProtocolPlanCommand.CoverageCommand>of() : coverageCommands;
        for (var c : commands) {
            validateCoverageEntry(c, kindByKey, seenKeys);
        }
        if (!seenKeys.containsAll(coverableKeys)) {
            var missing = new HashSet<>(coverableKeys);
            missing.removeAll(seenKeys);
            throw new DomainValidationException(
                    "Protocol plan coverage is missing entries: " + missing,
                    "research_run_protocol_plan_coverage_incomplete",
                    Map.of("missing_entry_keys", String.join(",", missing)));
        }
    }

    /**
     * Validates one coverage command: shape (blank/duplicate/unknown {@code
     * contractEntryKey}), that the targeted entry is coverable (not a {@code
     * METHOD_LIMIT}/{@code NON_CLAIM} constraint), that a disposition is present,
     * and — via {@link #validateCoverageDispositionFields} — that the
     * disposition's required fields are present and bounded.
     */
    private void validateCoverageEntry(
            RecordProtocolPlanCommand.CoverageCommand c,
            Map<String, ContractEntryKind> kindByKey,
            Set<String> seenKeys) {
        if (c == null || emptyToNull(c.contractEntryKey()) == null) {
            throw new DomainValidationException(
                    "contractEntryKey must not be blank", INVALID_CODE, Map.of(FIELD, CONTRACT_ENTRY_KEY_JSON_FIELD));
        }
        var key = c.contractEntryKey().trim();
        requireUnder(key, ENTRY_KEY_MAX, CONTRACT_ENTRY_KEY_JSON_FIELD);
        if (!seenKeys.add(key)) {
            throw new DomainValidationException(
                    "Duplicate protocol plan coverage for contract entry: " + key,
                    "research_run_protocol_plan_duplicate_coverage",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
        }
        var kind = kindByKey.get(key);
        if (kind == null) {
            throw new DomainValidationException(
                    "contractEntryKey '" + key
                            + "' does not match any entry in the active methodology requirements contract",
                    "research_run_protocol_plan_unknown_contract_entry",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
        }
        if (kind == ContractEntryKind.METHOD_LIMIT || kind == ContractEntryKind.NON_CLAIM) {
            throw new DomainValidationException(
                    "contractEntryKey '" + key + "' is a " + kind
                            + "; it is a constraint the plan carries forward, not a coverable answer",
                    "research_run_protocol_plan_entry_not_coverable",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key, "kind", kind.name()));
        }
        if (c.disposition() == null) {
            throw new DomainValidationException(
                    "disposition must not be null", INVALID_CODE, Map.of(FIELD, DISPOSITION_FIELD));
        }
        validateCoverageDispositionFields(c, key);
    }

    /**
     * Second validation pass: the fields a disposition requires are present and
     * bounded (ADR-083 §2). Each disposition's own completeness rule lives in a
     * dedicated {@code requireXxxComplete} method so this dispatcher never holds
     * conditional logic itself (S6916) — {@code switch} on a plain enum constant
     * cannot carry a {@code when} guard (JLS 14.11.1 restricts guards to pattern
     * case labels), so the per-branch condition is pushed into the callee instead.
     */
    private void validateCoverageDispositionFields(RecordProtocolPlanCommand.CoverageCommand c, String key) {
        requireUnder(c.answerSummary(), ANSWER_SUMMARY_MAX, "answerSummary");
        requireUnder(c.rationale(), PROTOCOL_RATIONALE_MAX, "rationale");
        requireUnder(c.decisionReference(), DECISION_REFERENCE_MAX, "decisionReference");
        switch (c.disposition()) {
            case FILLED -> requireFilledComplete(c, key);
            case DEFERRED_NON_BLOCKING -> requireDeferredNonBlockingComplete(c, key);
            case NOT_APPLICABLE_WITH_RATIONALE -> requireNotApplicableWithRationaleComplete(c, key);
            case RESOLVED_BY_USER_DECISION -> requireResolvedByUserDecisionComplete(c, key);
            case BLOCKING_DECISION_REQUIRED -> requireBlockingDecisionRequiredComplete(c, key);
            default -> {
                // Unreachable: every ProtocolCoverageDisposition constant is handled above.
                // Checkstyle's MissingSwitchDefault still requires this clause.
            }
        }
    }

    private void requireFilledComplete(RecordProtocolPlanCommand.CoverageCommand c, String key) {
        if (c.answerProvenance() == null || emptyToNull(c.answerSummary()) == null) {
            throw new DomainValidationException(
                    "FILLED coverage for '" + key + "' requires answerProvenance and answerSummary",
                    "research_run_protocol_plan_filled_incomplete",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
        }
    }

    private void requireDeferredNonBlockingComplete(RecordProtocolPlanCommand.CoverageCommand c, String key) {
        if (c.deferredToStage() == null || emptyToNull(c.rationale()) == null) {
            throw new DomainValidationException(
                    "DEFERRED_NON_BLOCKING coverage for '" + key + "' requires deferredToStage and rationale",
                    "research_run_protocol_plan_deferred_incomplete",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
        }
    }

    private void requireNotApplicableWithRationaleComplete(RecordProtocolPlanCommand.CoverageCommand c, String key) {
        if (emptyToNull(c.rationale()) == null) {
            throw new DomainValidationException(
                    "NOT_APPLICABLE_WITH_RATIONALE coverage for '" + key + "' requires rationale",
                    "research_run_protocol_plan_not_applicable_incomplete",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
        }
    }

    private void requireResolvedByUserDecisionComplete(RecordProtocolPlanCommand.CoverageCommand c, String key) {
        if (emptyToNull(c.decisionReference()) == null && emptyToNull(c.rationale()) == null) {
            throw new DomainValidationException(
                    "RESOLVED_BY_USER_DECISION coverage for '" + key + "' requires decisionReference or rationale",
                    "research_run_protocol_plan_resolved_incomplete",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
        }
    }

    private void requireBlockingDecisionRequiredComplete(RecordProtocolPlanCommand.CoverageCommand c, String key) {
        if (emptyToNull(c.rationale()) == null) {
            throw new DomainValidationException(
                    "BLOCKING_DECISION_REQUIRED coverage for '" + key + "' requires rationale",
                    "research_run_protocol_plan_blocking_incomplete",
                    Map.of(CONTRACT_ENTRY_KEY_JSON_FIELD, key));
        }
    }

    /**
     * Third validation pass: every section kind the selected method profile
     * requires is present ({@link ProtocolMethodShape}), section keys are
     * unique, and {@code sourceRole} is only assigned on a {@code
     * SOURCE_ROLES} section of the taxonomy-development method (ADR-083 §3).
     */
    private void validateProtocolPlanSections(
            List<RecordProtocolPlanCommand.SectionCommand> sectionCommands, String methodKey) {
        if (sectionCommands == null || sectionCommands.isEmpty()) {
            throw new DomainValidationException(
                    "Protocol plan must include at least one section", INVALID_CODE, Map.of(FIELD, "sections"));
        }
        var seenKeys = new HashSet<String>();
        var presentKinds = EnumSet.noneOf(ProtocolSectionKind.class);
        var isTaxonomy = ProtocolMethodShape.isTaxonomyDevelopment(methodKey);
        var taxonomySourceRoles = EnumSet.noneOf(ProtocolSourceRole.class);
        for (var s : sectionCommands) {
            presentKinds.add(validateSectionEntry(s, seenKeys, isTaxonomy, taxonomySourceRoles, methodKey));
        }
        var required = ProtocolMethodShape.requiredSections(methodKey);
        if (!presentKinds.containsAll(required)) {
            var missing = EnumSet.copyOf(required);
            missing.removeAll(presentKinds);
            throw new DomainValidationException(
                    "Protocol plan is missing required sections for method '" + methodKey + "': " + missing,
                    "research_run_protocol_plan_section_missing",
                    Map.of(
                            METHOD_KEY_FIELD,
                            methodKey == null ? "" : methodKey,
                            "missing_section_kinds",
                            missing.toString()));
        }
        if (isTaxonomy) {
            requireTaxonomySourceRolesComplete(taxonomySourceRoles, methodKey);
        }
    }

    /**
     * Validates one section command (blank/duplicate {@code sectionKey}, presence
     * of {@code sectionKind}/{@code contentSummary}, and the {@code sourceRole}
     * constraints of ADR-083 §3), recording its taxonomy source role — if any —
     * into {@code taxonomySourceRoles}, and returns its section kind for the
     * caller's required-sections check.
     */
    private ProtocolSectionKind validateSectionEntry(
            RecordProtocolPlanCommand.SectionCommand s,
            Set<String> seenKeys,
            boolean isTaxonomy,
            EnumSet<ProtocolSourceRole> taxonomySourceRoles,
            String methodKey) {
        if (s == null || emptyToNull(s.sectionKey()) == null) {
            throw new DomainValidationException(
                    "sectionKey must not be blank", INVALID_CODE, Map.of(FIELD, SECTION_KEY_JSON_FIELD));
        }
        var key = s.sectionKey().trim();
        requireUnder(key, SECTION_KEY_MAX, SECTION_KEY_JSON_FIELD);
        if (!seenKeys.add(key)) {
            throw new DomainValidationException(
                    "Duplicate protocol plan sectionKey: " + key,
                    "research_run_protocol_plan_duplicate_section_key",
                    Map.of(SECTION_KEY_JSON_FIELD, key));
        }
        if (s.sectionKind() == null) {
            throw new DomainValidationException(
                    "sectionKind must not be null", INVALID_CODE, Map.of(FIELD, SECTION_KIND_FIELD));
        }
        if (emptyToNull(s.contentSummary()) == null) {
            throw new DomainValidationException(
                    "contentSummary must not be blank", INVALID_CODE, Map.of(FIELD, "contentSummary"));
        }
        requireUnder(s.contentSummary(), SUMMARY_MAX, "contentSummary");
        var isTaxonomySourceRoleSection = isTaxonomy && s.sectionKind() == ProtocolSectionKind.SOURCE_ROLES;
        if (s.sourceRole() != null && !isTaxonomySourceRoleSection) {
            throw new DomainValidationException(
                    "sourceRole is only permitted on SOURCE_ROLES sections of the taxonomy-development method",
                    "research_run_protocol_plan_source_role_not_allowed",
                    Map.of(SECTION_KEY_JSON_FIELD, key, METHOD_KEY_FIELD, methodKey == null ? "" : methodKey));
        }
        // ADR-083 §3 — taxonomy source-role separation is the hard boundary case: a
        // SOURCE_ROLES section must name the role it carries so background/framing,
        // methodology, and validation material cannot collapse into the taxonomy corpus.
        if (isTaxonomySourceRoleSection) {
            if (s.sourceRole() == null) {
                throw new DomainValidationException(
                        "A taxonomy-development SOURCE_ROLES section must declare a sourceRole",
                        "research_run_protocol_plan_source_role_required",
                        Map.of(SECTION_KEY_JSON_FIELD, key));
            }
            taxonomySourceRoles.add(s.sourceRole());
        }
        return s.sectionKind();
    }

    /**
     * ADR-083 §3 — the accepted taxonomy plan must actually carry every distinct
     * source role, not merely permit them; otherwise later stages cannot rely on the
     * plan to keep background sources from supporting taxonomy claims.
     */
    private void requireTaxonomySourceRolesComplete(EnumSet<ProtocolSourceRole> taxonomySourceRoles, String methodKey) {
        var requiredRoles = EnumSet.allOf(ProtocolSourceRole.class);
        if (!taxonomySourceRoles.containsAll(requiredRoles)) {
            var missingRoles = EnumSet.copyOf(requiredRoles);
            missingRoles.removeAll(taxonomySourceRoles);
            throw new DomainValidationException(
                    "Taxonomy-development protocol plan must separate all source roles across SOURCE_ROLES"
                            + " sections; missing: " + missingRoles,
                    "research_run_protocol_plan_source_roles_incomplete",
                    Map.of(
                            METHOD_KEY_FIELD,
                            methodKey == null ? "" : methodKey,
                            "missing_source_roles",
                            missingRoles.toString()));
        }
    }

    /** Persists the plan aggregate (plan, coverage rows, section rows) in one transaction. */
    private ProtocolPlanAggregate persistProtocolPlan(
            ResearchRun run,
            MethodologyRequirementsContract contract,
            ResearchRunArtifact artifact,
            ResearchRunMethodologySelection selection,
            String schemaVersion,
            RecordProtocolPlanCommand command) {
        var actor = currentActor();
        var plan = new ProtocolPlan(
                run,
                contract,
                artifact.getId(),
                artifact.getAttemptNo(),
                schemaVersion,
                selection.getMethodKey(),
                selection.getProfileVersion(),
                actor);
        var savedPlan = protocolPlanRepository.save(plan);

        var savedCoverages = new ArrayList<ProtocolPlanCoverage>();
        if (command.coverages() != null) {
            for (var c : command.coverages()) {
                savedCoverages.add(protocolPlanCoverageRepository.save(new ProtocolPlanCoverage(
                        savedPlan,
                        c.contractEntryKey().trim(),
                        c.disposition(),
                        emptyToNull(c.answerSummary()),
                        c.answerProvenance(),
                        emptyToNull(c.rationale()),
                        c.deferredToStage(),
                        emptyToNull(c.decisionReference()),
                        actor)));
            }
        }

        var savedSections = new ArrayList<ProtocolPlanSection>();
        for (var s : command.sections()) {
            savedSections.add(protocolPlanSectionRepository.save(new ProtocolPlanSection(
                    savedPlan,
                    s.sectionKey().trim(),
                    s.sectionKind(),
                    s.sourceRole(),
                    s.contentSummary().trim(),
                    actor)));
        }

        log.info(
                "research_run_protocol_plan_recorded: project={} run={} artifact={} attempt={} coverages={} sections={}",
                run.getProject().getIdentifier(),
                run.getId(),
                artifact.getId(),
                artifact.getAttemptNo(),
                savedCoverages.size(),
                savedSections.size());

        return new ProtocolPlanAggregate(savedPlan, savedCoverages, savedSections);
    }
}
