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
 * GC-RSCH-F003 / GC-RSCH-F036 / ADR-064 §6–8 — durable lifecycle manifest /
 * checkpoint record. Each record proves a stage produced its output artifact and
 * is the gate authority for the next stage; it is bounded metadata only, never
 * the artifact content. Exactly one {@code ACTIVE} record of a given type exists
 * per run; rework {@code SUPERSEDED}s the prior record (it is never mutated in
 * place) and points the superseded row at its replacement. The optional
 * {@code idempotencyKey} (unique per run) lets a retrying caller reuse an
 * existing record instead of duplicating work.
 */
@Entity
@Audited
@Table(name = "research_run_artifact")
public class ResearchRunArtifact extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchRunStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 40)
    private ResearchArtifactType artifactType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchArtifactStatus status = ResearchArtifactStatus.ACTIVE;

    @Column(length = 500)
    private String locator;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @NotAudited
    @Column(name = "superseded_by_artifact_id")
    private UUID supersededByArtifactId;

    @Column(length = 200)
    private String actor;

    protected ResearchRunArtifact() {
        // JPA
    }

    public ResearchRunArtifact(ResearchRun researchRun, ResearchArtifactType artifactType, int attemptNo) {
        if (researchRun == null) {
            throw new DomainValidationException("Research run must not be null", "invalid_research_artifact", Map.of());
        }
        if (artifactType == null) {
            throw new DomainValidationException(
                    "Artifact type must not be null", "invalid_research_artifact", Map.of());
        }
        this.researchRun = researchRun;
        this.artifactType = artifactType;
        this.stage = artifactType.producingStage();
        this.attemptNo = Math.max(1, attemptNo);
    }

    /**
     * Mark this record {@code SUPERSEDED} ahead of inserting its replacement. The
     * replacement link is set afterwards via {@link #linkSuperseder(UUID)} once the
     * new record has an id. Splitting the two phases lets the service flush this
     * status change before inserting the new {@code ACTIVE} row, so the
     * single-active-artifact partial unique index is never transiently violated
     * (Hibernate otherwise orders the insert before this update).
     */
    public void markSuperseded() {
        this.status = ResearchArtifactStatus.SUPERSEDED;
    }

    /** Point this superseded record at the {@code ACTIVE} record that replaced it. */
    public void linkSuperseder(UUID replacementArtifactId) {
        this.supersededByArtifactId = replacementArtifactId;
    }

    public void markFailed() {
        this.status = ResearchArtifactStatus.FAILED;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public ResearchRunStage getStage() {
        return stage;
    }

    public ResearchArtifactType getArtifactType() {
        return artifactType;
    }

    public ResearchArtifactStatus getStatus() {
        return status;
    }

    public String getLocator() {
        return locator;
    }

    public void setLocator(String locator) {
        this.locator = locator;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public UUID getSupersededByArtifactId() {
        return supersededByArtifactId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }
}
