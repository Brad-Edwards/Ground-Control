package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-F004 / ADR-066 — append-only durable record of one gate decision. A
 * row is written every time a gate is resolved (human, autonomous default, or
 * otherwise), capturing the recommendation that was on the table and the
 * decision that was made. Rows are never mutated or deleted: a rework reopens
 * the gate and a fresh decision appends a new row, preserving the full decision
 * history (never in workspace {@code decisions.md}).
 */
@Entity
@Audited
@Table(name = "research_run_gate_decision_log")
public class ResearchRunGateDecisionLog extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_point", nullable = false, length = 40)
    private ResearchGatePoint gatePoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "guarded_stage", nullable = false, length = 40)
    private ResearchRunStage guardedStage;

    @Column(name = "artifact_attempt_no")
    private Integer artifactAttemptNo;

    @Column(name = "question_key", length = 200)
    private String questionKey;

    @Column(name = "recommendation_option_id", length = 200)
    private String recommendationOptionId;

    @Column(name = "recommendation_summary", length = 1000)
    private String recommendationSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation_provenance", length = 20)
    private GateRecommendationProvenance recommendationProvenance;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_outcome", nullable = false, length = 20)
    private ResearchGateDecisionOutcome decisionOutcome;

    @Column(name = "selected_option_id", length = 200)
    private String selectedOptionId;

    @Column(name = "rationale_summary", length = 1000)
    private String rationaleSummary;

    @Column(name = "policy_basis", length = 200)
    private String policyBasis;

    @Column(name = "source_action_id", length = 200)
    private String sourceActionId;

    @Column(name = "decision_actor", length = 200)
    private String decisionActor;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected ResearchRunGateDecisionLog() {
        // JPA
    }

    public ResearchRunGateDecisionLog(
            ResearchRun researchRun,
            ResearchGatePoint gatePoint,
            ResearchRunStage guardedStage,
            ResearchGateDecisionOutcome decisionOutcome,
            String decisionActor,
            Instant decidedAt) {
        if (researchRun == null) {
            throw new DomainValidationException(
                    "Research run must not be null", "invalid_research_decision_log", Map.of());
        }
        if (gatePoint == null) {
            throw new DomainValidationException(
                    "Gate point must not be null", "invalid_research_decision_log", Map.of());
        }
        if (guardedStage == null) {
            throw new DomainValidationException(
                    "Guarded stage must not be null", "invalid_research_decision_log", Map.of());
        }
        if (decisionOutcome == null) {
            throw new DomainValidationException(
                    "Decision outcome must not be null", "invalid_research_decision_log", Map.of());
        }
        this.researchRun = researchRun;
        this.gatePoint = gatePoint;
        this.guardedStage = guardedStage;
        this.decisionOutcome = decisionOutcome;
        this.decisionActor = decisionActor;
        this.decidedAt = decidedAt;
    }

    public void setArtifactAttemptNo(Integer artifactAttemptNo) {
        this.artifactAttemptNo = artifactAttemptNo;
    }

    public void setQuestionKey(String questionKey) {
        this.questionKey = questionKey;
    }

    public void setRecommendationOptionId(String recommendationOptionId) {
        this.recommendationOptionId = recommendationOptionId;
    }

    public void setRecommendationSummary(String recommendationSummary) {
        this.recommendationSummary = recommendationSummary;
    }

    public void setRecommendationProvenance(GateRecommendationProvenance recommendationProvenance) {
        this.recommendationProvenance = recommendationProvenance;
    }

    public void setSelectedOptionId(String selectedOptionId) {
        this.selectedOptionId = selectedOptionId;
    }

    public void setRationaleSummary(String rationaleSummary) {
        this.rationaleSummary = rationaleSummary;
    }

    public void setPolicyBasis(String policyBasis) {
        this.policyBasis = policyBasis;
    }

    public void setSourceActionId(String sourceActionId) {
        this.sourceActionId = sourceActionId;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public ResearchGatePoint getGatePoint() {
        return gatePoint;
    }

    public ResearchRunStage getGuardedStage() {
        return guardedStage;
    }

    public Integer getArtifactAttemptNo() {
        return artifactAttemptNo;
    }

    public String getQuestionKey() {
        return questionKey;
    }

    public String getRecommendationOptionId() {
        return recommendationOptionId;
    }

    public String getRecommendationSummary() {
        return recommendationSummary;
    }

    public GateRecommendationProvenance getRecommendationProvenance() {
        return recommendationProvenance;
    }

    public ResearchGateDecisionOutcome getDecisionOutcome() {
        return decisionOutcome;
    }

    public String getSelectedOptionId() {
        return selectedOptionId;
    }

    public String getRationaleSummary() {
        return rationaleSummary;
    }

    public String getPolicyBasis() {
        return policyBasis;
    }

    public String getSourceActionId() {
        return sourceActionId;
    }

    public String getDecisionActor() {
        return decisionActor;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
