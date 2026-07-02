package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.EntryCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.RejectedAlternativeCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordMethodologyRequirementsContractCommand.SourceLinkCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * GC-RSCH-F007 / ADR-080 — record the methodology requirements contract for the
 * run's ACTIVE {@code METHODOLOGY_REQUIREMENTS} artifact attempt. Deliberately
 * has no fields for domain answers (databases, queries, date ranges, inclusion
 * criteria, charting categories, synthesis dimensions, source caps): the
 * phase-1/phase-2 boundary is structural (ADR-080 §4). Actor, chosen method,
 * artifact id, and attempt are resolved server-side (ADR-026), never from this
 * body.
 */
public record RecordMethodologyRequirementsContractRequest(
        @NotEmpty @Valid List<EntryRequest> entries, @Valid List<RejectedAlternativeRequest> rejectedAlternatives) {

    /** One extracted entry. Source grounding rules are enforced server-side by kind. */
    public record EntryRequest(
            @NotNull ContractEntryKind kind,
            @NotBlank @Size(max = 200) String entryKey,
            @NotBlank @Size(max = 2000) String statement,
            @Valid List<SourceLinkRequest> sourceLinks,
            @Size(max = 200) String referencesEntryKey) {}

    /** Grounds an entry in a methodology source of the active selection. */
    public record SourceLinkRequest(@NotNull UUID sourceId, @Size(max = 500) String locator) {}

    /** A rejected methodology alternative, optionally tied to its METHODOLOGY_CHOICE rationale. */
    public record RejectedAlternativeRequest(
            @NotBlank @Size(max = 200) String methodKey,
            @Size(max = 100) String profileVersion,
            UUID rationaleEntryId,
            boolean external) {}

    public RecordMethodologyRequirementsContractCommand toCommand() {
        var entryCommands = entries == null
                ? List.<EntryCommand>of()
                : entries.stream()
                        .map(RecordMethodologyRequirementsContractRequest::toEntryCommand)
                        .toList();
        var rejectedCommands = rejectedAlternatives == null
                ? List.<RejectedAlternativeCommand>of()
                : rejectedAlternatives.stream()
                        .map(RecordMethodologyRequirementsContractRequest::toRejectedAlternativeCommand)
                        .toList();
        return new RecordMethodologyRequirementsContractCommand(entryCommands, rejectedCommands);
    }

    private static EntryCommand toEntryCommand(EntryRequest e) {
        return new EntryCommand(
                e.kind(), e.entryKey(), e.statement(), toSourceLinkCommands(e.sourceLinks()), e.referencesEntryKey());
    }

    private static List<SourceLinkCommand> toSourceLinkCommands(List<SourceLinkRequest> sourceLinks) {
        if (sourceLinks == null) {
            return List.of();
        }
        return sourceLinks.stream()
                .map(s -> new SourceLinkCommand(s.sourceId(), s.locator()))
                .toList();
    }

    private static RejectedAlternativeCommand toRejectedAlternativeCommand(RejectedAlternativeRequest r) {
        return new RejectedAlternativeCommand(r.methodKey(), r.profileVersion(), r.rationaleEntryId(), r.external());
    }
}
