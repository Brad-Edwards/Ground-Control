package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ProtocolAnswerProvenance;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSourceRole;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand.CoverageCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProtocolPlanCommand.SectionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * GC-RSCH-F008 / GC-RSCH-F009 / ADR-083 — record the protocol plan for the
 * run's ACTIVE {@code PROTOCOL_PLAN} artifact attempt. Method key, profile
 * version, methodology contract id/attempt, and artifact attempt are resolved
 * server-side (ADR-026), never from this body.
 */
public record RecordProtocolPlanRequest(
        @NotBlank @Size(max = 40) String protocolSchemaVersion,
        @NotEmpty @Valid List<CoverageRequest> coverages,
        @NotEmpty @Valid List<SectionRequest> sections) {

    /** One coverage disposition for an ADR-080 contract entry. Field requirements are enforced server-side by disposition. */
    public record CoverageRequest(
            @NotBlank @Size(max = 200) String contractEntryKey,
            @NotNull ProtocolCoverageDisposition disposition,
            @Size(max = 2000) String answerSummary,
            ProtocolAnswerProvenance answerProvenance,
            @Size(max = 2000) String rationale,
            ResearchRunStage deferredToStage,
            @Size(max = 200) String decisionReference) {}

    /** One method-specific output section. */
    public record SectionRequest(
            @NotBlank @Size(max = 200) String sectionKey,
            @NotNull ProtocolSectionKind sectionKind,
            ProtocolSourceRole sourceRole,
            @NotBlank @Size(max = 2000) String contentSummary) {}

    public RecordProtocolPlanCommand toCommand() {
        var coverageCommands = coverages == null
                ? List.<CoverageCommand>of()
                : coverages.stream()
                        .map(RecordProtocolPlanRequest::toCoverageCommand)
                        .toList();
        var sectionCommands = sections == null
                ? List.<SectionCommand>of()
                : sections.stream()
                        .map(RecordProtocolPlanRequest::toSectionCommand)
                        .toList();
        return new RecordProtocolPlanCommand(protocolSchemaVersion, coverageCommands, sectionCommands);
    }

    private static CoverageCommand toCoverageCommand(CoverageRequest c) {
        return new CoverageCommand(
                c.contractEntryKey(),
                c.disposition(),
                c.answerSummary(),
                c.answerProvenance(),
                c.rationale(),
                c.deferredToStage(),
                c.decisionReference());
    }

    private static SectionCommand toSectionCommand(SectionRequest s) {
        return new SectionCommand(s.sectionKey(), s.sectionKind(), s.sourceRole(), s.contentSummary());
    }
}
