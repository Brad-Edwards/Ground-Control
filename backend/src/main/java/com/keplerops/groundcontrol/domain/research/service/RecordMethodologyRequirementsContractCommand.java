package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import java.util.List;
import java.util.UUID;

/**
 * GC-RSCH-F007 / ADR-079 — record the structured methodology requirements
 * contract behind the run's ACTIVE {@code METHODOLOGY_REQUIREMENTS} artifact
 * attempt. The chosen method, artifact id, and attempt are resolved server-side
 * from the run's active selection and active artifact, not this command. The
 * recording actor comes from the authenticated server context (ADR-026).
 *
 * <p>The command shape deliberately has no first-class fields for domain answers
 * (databases, query strings, date ranges, inclusion/exclusion values, charting
 * categories, synthesis dimensions, source-set caps): that phase-1/phase-2
 * boundary is structural (ADR-079 §4).
 */
public record RecordMethodologyRequirementsContractCommand(
        List<EntryCommand> entries, List<RejectedAlternativeCommand> rejectedAlternatives) {

    /**
     * One extracted entry. {@code REQUIREMENT} / {@code METHOD_LIMIT} /
     * {@code NON_CLAIM} require at least one source link; {@code
     * OPEN_PROTOCOL_QUESTION} may instead reference other entries by key.
     */
    public record EntryCommand(
            ContractEntryKind kind,
            String entryKey,
            String statement,
            List<SourceLinkCommand> sourceLinks,
            String referencesEntryKey) {}

    /** Grounds an entry in a methodology source of the active selection (must be READ). */
    public record SourceLinkCommand(UUID sourceId, String locator) {}

    /** A rejected methodology alternative; {@code rationaleEntryId} points at its METHODOLOGY_CHOICE rationale. */
    public record RejectedAlternativeCommand(
            String methodKey, String profileVersion, UUID rationaleEntryId, boolean external) {}
}
