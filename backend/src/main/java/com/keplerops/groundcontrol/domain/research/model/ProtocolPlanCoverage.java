package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * GC-RSCH-F008 / ADR-081 §2 — one plan coverage row disposing of a single
 * ADR-080 {@code REQUIREMENT} or {@code OPEN_PROTOCOL_QUESTION} contract entry
 * (keyed by its stable {@code entryKey}, {@code contractEntryKey} here). Rows
 * are written once with the plan (immutable snapshot), so they are not
 * separately audited. The active {@link
 * com.keplerops.groundcontrol.domain.research.service.ResearchRunService}
 * refuses to let {@code SOURCE_SEARCH} start while any coverage in the active
 * plan is {@link ProtocolCoverageDisposition#BLOCKING_DECISION_REQUIRED}.
 */
@Entity
@Table(
        name = "protocol_plan_coverage",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_protocol_plan_coverage_key",
                        columnNames = {"protocol_plan_id", "contract_entry_key"}))
public class ProtocolPlanCoverage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "protocol_plan_id", nullable = false)
    private ProtocolPlan protocolPlan;

    @Column(name = "contract_entry_key", nullable = false, length = 200)
    private String contractEntryKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProtocolCoverageDisposition disposition;

    @Column(name = "answer_summary", length = 2000)
    private String answerSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_provenance", length = 40)
    private ProtocolAnswerProvenance answerProvenance;

    @Column(length = 2000)
    private String rationale;

    @Enumerated(EnumType.STRING)
    @Column(name = "deferred_to_stage", length = 40)
    private ResearchRunStage deferredToStage;

    @Column(name = "decision_reference", length = 200)
    private String decisionReference;

    @Column(length = 200)
    private String actor;

    protected ProtocolPlanCoverage() {
        // JPA
    }

    public ProtocolPlanCoverage(
            ProtocolPlan protocolPlan,
            String contractEntryKey,
            ProtocolCoverageDisposition disposition,
            String answerSummary,
            ProtocolAnswerProvenance answerProvenance,
            String rationale,
            ResearchRunStage deferredToStage,
            String decisionReference,
            String actor) {
        this.protocolPlan = protocolPlan;
        this.contractEntryKey = contractEntryKey;
        this.disposition = disposition;
        this.answerSummary = answerSummary;
        this.answerProvenance = answerProvenance;
        this.rationale = rationale;
        this.deferredToStage = deferredToStage;
        this.decisionReference = decisionReference;
        this.actor = actor;
    }

    public ProtocolPlan getProtocolPlan() {
        return protocolPlan;
    }

    public String getContractEntryKey() {
        return contractEntryKey;
    }

    public ProtocolCoverageDisposition getDisposition() {
        return disposition;
    }

    public String getAnswerSummary() {
        return answerSummary;
    }

    public ProtocolAnswerProvenance getAnswerProvenance() {
        return answerProvenance;
    }

    public String getRationale() {
        return rationale;
    }

    public ResearchRunStage getDeferredToStage() {
        return deferredToStage;
    }

    public String getDecisionReference() {
        return decisionReference;
    }

    public String getActor() {
        return actor;
    }
}
