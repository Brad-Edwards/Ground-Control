package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ProtocolAnswerProvenance;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSourceRole;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.util.List;

/**
 * GC-RSCH-F008 / GC-RSCH-F009 / ADR-081 — record the structured protocol plan
 * behind the run's ACTIVE {@code PROTOCOL_PLAN} artifact attempt. The method
 * key, method profile version, methodology contract id/attempt, and artifact
 * attempt are resolved server-side from the run's active selection and active
 * artifacts, not this command. The recording actor comes from the
 * authenticated server context (ADR-026).
 */
public record RecordProtocolPlanCommand(
        String protocolSchemaVersion, List<CoverageCommand> coverages, List<SectionCommand> sections) {

    /**
     * One coverage disposition for an ADR-080 {@code REQUIREMENT} or {@code
     * OPEN_PROTOCOL_QUESTION} contract entry. Which fields are required depends
     * on {@code disposition} (ADR-081 §2).
     */
    public record CoverageCommand(
            String contractEntryKey,
            ProtocolCoverageDisposition disposition,
            String answerSummary,
            ProtocolAnswerProvenance answerProvenance,
            String rationale,
            ResearchRunStage deferredToStage,
            String decisionReference) {}

    /** One method-specific output section. {@code sourceRole} is legal only for taxonomy source-role sections. */
    public record SectionCommand(
            String sectionKey, ProtocolSectionKind sectionKind, ProtocolSourceRole sourceRole, String contentSummary) {}
}
