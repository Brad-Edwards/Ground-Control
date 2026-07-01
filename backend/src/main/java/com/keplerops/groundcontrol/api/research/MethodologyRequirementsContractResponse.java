package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.service.MethodologyRequirementsContractAggregate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GC-RSCH-F007 / GC-RSCH-F008 / ADR-079 — the methodology requirements contract
 * as protocol planning consumes it: the chosen method (selection), the artifact
 * attempt it belongs to, extracted entries with stable keys and their source
 * links, and rejected alternatives. Bounded metadata only — never source text.
 */
public record MethodologyRequirementsContractResponse(
        UUID id,
        UUID researchRunId,
        UUID selectionId,
        String methodKey,
        UUID artifactId,
        int attemptNo,
        String schemaVersion,
        Instant createdAt,
        List<EntryResponse> entries,
        List<RejectedAlternativeResponse> rejectedAlternatives) {

    /** One extracted entry with its source grounding. */
    public record EntryResponse(
            UUID id,
            ContractEntryKind kind,
            String entryKey,
            String statement,
            String referencesEntryKey,
            List<SourceLinkResponse> sourceLinks) {}

    /** A methodology source that grounds an entry, with its stable reference. */
    public record SourceLinkResponse(UUID sourceId, String sourceRef, String locator) {}

    /** A rejected methodology alternative and its rationale reference. */
    public record RejectedAlternativeResponse(
            String methodKey, String profileVersion, UUID rationaleEntryId, boolean external) {}

    public static MethodologyRequirementsContractResponse from(MethodologyRequirementsContractAggregate aggregate) {
        var contract = aggregate.contract();

        // Group source links by their owning entry id.
        Map<UUID, List<SourceLinkResponse>> linksByEntry = new LinkedHashMap<>();
        for (var link : aggregate.sourceLinks()) {
            linksByEntry
                    .computeIfAbsent(link.getEntry().getId(), k -> new ArrayList<>())
                    .add(new SourceLinkResponse(
                            link.getSource().getId(), link.getSource().getSourceRef(), link.getLocator()));
        }

        var entries = aggregate.entries().stream()
                .map(e -> new EntryResponse(
                        e.getId(),
                        e.getKind(),
                        e.getEntryKey(),
                        e.getStatement(),
                        e.getReferencesEntryKey(),
                        linksByEntry.getOrDefault(e.getId(), List.of())))
                .toList();

        var rejected = aggregate.rejectedAlternatives().stream()
                .map(r -> new RejectedAlternativeResponse(
                        r.getMethodKey(), r.getProfileVersion(), r.getRationaleEntryId(), r.isExternal()))
                .toList();

        return new MethodologyRequirementsContractResponse(
                contract.getId(),
                contract.getResearchRun().getId(),
                contract.getSelection().getId(),
                contract.getSelection().getMethodKey(),
                contract.getArtifactId(),
                contract.getAttemptNo(),
                contract.getSchemaVersion(),
                contract.getCreatedAt(),
                entries,
                rejected);
    }
}
