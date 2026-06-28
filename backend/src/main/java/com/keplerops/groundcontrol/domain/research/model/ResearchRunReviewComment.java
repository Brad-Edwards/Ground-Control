package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
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
 * GC-RSCH-F034 / ADR-067 — a run-scoped review comment attached to a gate point,
 * stage, artifact, decision-log row, or the run itself. Comments open {@code
 * OPEN} and are resolved (or reopened) durably; the body is a bounded note, never
 * raw manuscript prose. Resolving and reopening are the only state transitions.
 */
@Entity
@Audited
@Table(name = "research_run_review_comment")
public class ResearchRunReviewComment extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReviewCommentTarget targetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_gate_point", length = 40)
    private ResearchGatePoint targetGatePoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_stage", length = 40)
    private ResearchRunStage targetStage;

    @Column(name = "target_artifact_id")
    private UUID targetArtifactId;

    @Column(name = "target_decision_log_id")
    private UUID targetDecisionLogId;

    @Column(nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewCommentProvenance provenance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewCommentStatus status = ReviewCommentStatus.OPEN;

    @Column(name = "resolution_summary", length = 1000)
    private String resolutionSummary;

    @Column(name = "author_actor", length = 200)
    private String authorActor;

    @Column(name = "resolved_by_actor", length = 200)
    private String resolvedByActor;

    protected ResearchRunReviewComment() {
        // JPA
    }

    public ResearchRunReviewComment(
            ResearchRun researchRun,
            ReviewCommentTarget targetType,
            String body,
            ReviewCommentProvenance provenance,
            String authorActor) {
        if (researchRun == null) {
            throw new DomainValidationException(
                    "Research run must not be null", "invalid_research_review_comment", Map.of());
        }
        if (targetType == null) {
            throw new DomainValidationException(
                    "Target type must not be null", "invalid_research_review_comment", Map.of());
        }
        if (body == null || body.isBlank()) {
            throw new DomainValidationException(
                    "Comment body must not be blank", "invalid_research_review_comment", Map.of());
        }
        if (provenance == null) {
            throw new DomainValidationException(
                    "Provenance must not be null", "invalid_research_review_comment", Map.of());
        }
        this.researchRun = researchRun;
        this.targetType = targetType;
        this.body = body;
        this.provenance = provenance;
        this.authorActor = authorActor;
    }

    public void setTargetGatePoint(ResearchGatePoint targetGatePoint) {
        this.targetGatePoint = targetGatePoint;
    }

    public void setTargetStage(ResearchRunStage targetStage) {
        this.targetStage = targetStage;
    }

    public void setTargetArtifactId(UUID targetArtifactId) {
        this.targetArtifactId = targetArtifactId;
    }

    public void setTargetDecisionLogId(UUID targetDecisionLogId) {
        this.targetDecisionLogId = targetDecisionLogId;
    }

    /** Resolve an open comment; rejected if already resolved (ADR-067). */
    public void resolve(String summary, String actor) {
        if (status == ReviewCommentStatus.RESOLVED) {
            throw new ConflictException(
                    "Review comment is already resolved",
                    "research_review_comment_already_resolved",
                    Map.of("status", status.name()));
        }
        this.status = ReviewCommentStatus.RESOLVED;
        this.resolutionSummary = summary;
        this.resolvedByActor = actor;
    }

    /** Reopen a resolved comment, clearing its resolution. */
    public void reopen() {
        this.status = ReviewCommentStatus.OPEN;
        this.resolutionSummary = null;
        this.resolvedByActor = null;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public ReviewCommentTarget getTargetType() {
        return targetType;
    }

    public ResearchGatePoint getTargetGatePoint() {
        return targetGatePoint;
    }

    public ResearchRunStage getTargetStage() {
        return targetStage;
    }

    public UUID getTargetArtifactId() {
        return targetArtifactId;
    }

    public UUID getTargetDecisionLogId() {
        return targetDecisionLogId;
    }

    public String getBody() {
        return body;
    }

    public ReviewCommentProvenance getProvenance() {
        return provenance;
    }

    public ReviewCommentStatus getStatus() {
        return status;
    }

    public String getResolutionSummary() {
        return resolutionSummary;
    }

    public String getAuthorActor() {
        return authorActor;
    }

    public String getResolvedByActor() {
        return resolvedByActor;
    }
}
