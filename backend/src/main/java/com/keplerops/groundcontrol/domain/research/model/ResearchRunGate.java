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
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-R003 / ADR-063 §4–5 — run-scoped human-gate policy snapshot combined
 * with its durable decision record. One row per {@link ResearchGatePoint} is
 * created at run start with the {@link ResearchGateBehavior} resolved from the
 * run's autonomy level and explicit overrides. Resolving the gate records the
 * decision durably (never in {@code decisions.md}). A rework of the guarded
 * stage's artifact re-opens the gate so the decision must be re-made.
 */
@Entity
@Audited
@Table(name = "research_run_gate")
public class ResearchRunGate extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_point", nullable = false, length = 40)
    private ResearchGatePoint gatePoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchGateBehavior behavior;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchGateStatus status = ResearchGateStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_outcome", length = 20)
    private ResearchGateDecisionOutcome decisionOutcome;

    @Column(name = "selected_option_id", length = 200)
    private String selectedOptionId;

    @Column(name = "rationale_summary", length = 1000)
    private String rationaleSummary;

    @Column(name = "policy_basis", length = 200)
    private String policyBasis;

    @Column(name = "resolved_by_actor", length = 200)
    private String resolvedByActor;

    protected ResearchRunGate() {
        // JPA
    }

    public ResearchRunGate(
            ResearchRun researchRun, ResearchGatePoint gatePoint, ResearchGateBehavior behavior, String policyBasis) {
        if (researchRun == null) {
            throw new DomainValidationException("Research run must not be null", "invalid_research_gate", Map.of());
        }
        if (gatePoint == null) {
            throw new DomainValidationException("Gate point must not be null", "invalid_research_gate", Map.of());
        }
        if (behavior == null) {
            throw new DomainValidationException("Gate behavior must not be null", "invalid_research_gate", Map.of());
        }
        this.researchRun = researchRun;
        this.gatePoint = gatePoint;
        this.behavior = behavior;
        this.policyBasis = policyBasis;
        // A disabled gate carries no human decision; it is resolved-on-create.
        if (behavior == ResearchGateBehavior.DISABLED) {
            this.status = ResearchGateStatus.RESOLVED;
            this.decisionOutcome = ResearchGateDecisionOutcome.AUTO_ACCEPTED;
        }
    }

    /** Record a durable decision for this gate. */
    public void resolve(ResearchGateDecisionOutcome outcome, String optionId, String rationaleSummary, String actor) {
        if (outcome == null) {
            throw new DomainValidationException(
                    "Gate decision outcome must not be null",
                    "research_gate_decision_invalid",
                    Map.of("gate_point", gatePoint.name()));
        }
        this.status = ResearchGateStatus.RESOLVED;
        this.decisionOutcome = outcome;
        this.selectedOptionId = optionId;
        this.rationaleSummary = rationaleSummary;
        this.resolvedByActor = actor;
    }

    /** Re-open the gate for a fresh decision after the guarded artifact is reworked. */
    public void reopen() {
        this.status = ResearchGateStatus.PENDING;
        this.decisionOutcome = null;
        this.selectedOptionId = null;
        this.rationaleSummary = null;
        this.resolvedByActor = null;
    }

    /** Whether the gate's current state permits the guarded stage exit to proceed. */
    public boolean permitsAdvance() {
        if (behavior == ResearchGateBehavior.DISABLED) {
            return true;
        }
        return status == ResearchGateStatus.RESOLVED && decisionOutcome != null && decisionOutcome.permitsAdvance();
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public ResearchGatePoint getGatePoint() {
        return gatePoint;
    }

    public ResearchGateBehavior getBehavior() {
        return behavior;
    }

    public ResearchGateStatus getStatus() {
        return status;
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

    public String getResolvedByActor() {
        return resolvedByActor;
    }
}
