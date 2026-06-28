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
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-N013 / ADR-068 §4 — the AI-use and unresolved-uncertainty disclosure
 * tied to a run's final manuscript artifact. A disclosure is {@code CURRENT}
 * until that manuscript is superseded, which marks it {@code STALE}. Completion
 * gating requires a {@code CURRENT} disclosure whose {@code finalArtifactId}
 * matches the active manuscript and whose two declaration families are covered.
 */
@Entity
@Audited
@Table(name = "research_run_disclosure")
public class ResearchRunDisclosure extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Column(name = "final_artifact_id", nullable = false)
    private UUID finalArtifactId;

    @Column(name = "final_attempt_no", nullable = false)
    private Integer finalAttemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DisclosureStatus status = DisclosureStatus.CURRENT;

    @Column(name = "ai_parts_declared_none", nullable = false)
    private boolean aiPartsDeclaredNone;

    @Column(name = "uncertainty_declared_none", nullable = false)
    private boolean uncertaintyDeclaredNone;

    @Column(name = "human_approvals_declared_none", nullable = false)
    private boolean humanApprovalsDeclaredNone;

    @Column(length = 200)
    private String actor;

    protected ResearchRunDisclosure() {
        // JPA
    }

    public ResearchRunDisclosure(
            ResearchRun researchRun,
            UUID finalArtifactId,
            Integer finalAttemptNo,
            boolean aiPartsDeclaredNone,
            boolean uncertaintyDeclaredNone,
            boolean humanApprovalsDeclaredNone,
            String actor) {
        if (researchRun == null) {
            throw new DomainValidationException(
                    "Research run must not be null", "invalid_research_disclosure", Map.of());
        }
        if (finalArtifactId == null) {
            throw new DomainValidationException(
                    "Final artifact id must not be null", "invalid_research_disclosure", Map.of());
        }
        if (finalAttemptNo == null) {
            throw new DomainValidationException(
                    "Final attempt number must not be null", "invalid_research_disclosure", Map.of());
        }
        this.researchRun = researchRun;
        this.finalArtifactId = finalArtifactId;
        this.finalAttemptNo = finalAttemptNo;
        this.aiPartsDeclaredNone = aiPartsDeclaredNone;
        this.uncertaintyDeclaredNone = uncertaintyDeclaredNone;
        this.humanApprovalsDeclaredNone = humanApprovalsDeclaredNone;
        this.actor = actor;
    }

    /** Mark this disclosure stale when its tied manuscript is superseded. */
    public void markStale() {
        this.status = DisclosureStatus.STALE;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public UUID getFinalArtifactId() {
        return finalArtifactId;
    }

    public Integer getFinalAttemptNo() {
        return finalAttemptNo;
    }

    public DisclosureStatus getStatus() {
        return status;
    }

    public boolean isAiPartsDeclaredNone() {
        return aiPartsDeclaredNone;
    }

    public boolean isUncertaintyDeclaredNone() {
        return uncertaintyDeclaredNone;
    }

    public boolean isHumanApprovalsDeclaredNone() {
        return humanApprovalsDeclaredNone;
    }

    public String getActor() {
        return actor;
    }
}
