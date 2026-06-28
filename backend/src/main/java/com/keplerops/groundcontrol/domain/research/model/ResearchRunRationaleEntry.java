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
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-N012 / ADR-068 — an immutable entry in a run's rationale ledger. Each
 * row records why a single load-bearing decision was made (a methodology choice,
 * a search/exclusion call, a charted value, a synthesis or writing claim), its
 * evidentiary basis, and its provenance. Entries are append-only and never
 * mutated; the ledger is the durable, auditable trail behind the manuscript.
 */
@Entity
@Audited
@Table(name = "research_run_rationale_entry")
public class ResearchRunRationaleEntry extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchRunStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", length = 40)
    private ResearchArtifactType artifactType;

    @Column(name = "artifact_id")
    private UUID artifactId;

    @Column(name = "attempt_no")
    private Integer attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_point", length = 40)
    private ResearchGatePoint gatePoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RationaleEntryKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_basis", nullable = false, length = 30)
    private RationaleEvidenceBasis evidenceBasis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RationaleProvenance provenance;

    @Column(name = "subject_key", nullable = false, length = 200)
    private String subjectKey;

    @Column(name = "rationale_summary", nullable = false, length = 2000)
    private String rationaleSummary;

    @Column(name = "evidence_locator", length = 500)
    private String evidenceLocator;

    @Column(name = "confidence_summary", length = 500)
    private String confidenceSummary;

    @Column(length = 200)
    private String actor;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    protected ResearchRunRationaleEntry() {
        // JPA
    }

    public ResearchRunRationaleEntry(
            ResearchRun researchRun,
            ResearchRunStage stage,
            RationaleEntryKind kind,
            RationaleEvidenceBasis evidenceBasis,
            RationaleProvenance provenance,
            String subjectKey,
            String rationaleSummary,
            String actor,
            Instant recordedAt) {
        if (researchRun == null) {
            throw new DomainValidationException(
                    "Research run must not be null", "invalid_research_rationale_entry", Map.of());
        }
        if (stage == null) {
            throw new DomainValidationException("Stage must not be null", "invalid_research_rationale_entry", Map.of());
        }
        if (kind == null) {
            throw new DomainValidationException("Kind must not be null", "invalid_research_rationale_entry", Map.of());
        }
        if (evidenceBasis == null) {
            throw new DomainValidationException(
                    "Evidence basis must not be null", "invalid_research_rationale_entry", Map.of());
        }
        if (provenance == null) {
            throw new DomainValidationException(
                    "Provenance must not be null", "invalid_research_rationale_entry", Map.of());
        }
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new DomainValidationException(
                    "Subject key must not be blank", "invalid_research_rationale_entry", Map.of());
        }
        if (rationaleSummary == null || rationaleSummary.isBlank()) {
            throw new DomainValidationException(
                    "Rationale summary must not be blank", "invalid_research_rationale_entry", Map.of());
        }
        this.researchRun = researchRun;
        this.stage = stage;
        this.kind = kind;
        this.evidenceBasis = evidenceBasis;
        this.provenance = provenance;
        this.subjectKey = subjectKey;
        this.rationaleSummary = rationaleSummary;
        this.actor = actor;
        this.recordedAt = recordedAt;
    }

    public void setArtifactType(ResearchArtifactType artifactType) {
        this.artifactType = artifactType;
    }

    public void setArtifactId(UUID artifactId) {
        this.artifactId = artifactId;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public void setGatePoint(ResearchGatePoint gatePoint) {
        this.gatePoint = gatePoint;
    }

    public void setEvidenceLocator(String evidenceLocator) {
        this.evidenceLocator = evidenceLocator;
    }

    public void setConfidenceSummary(String confidenceSummary) {
        this.confidenceSummary = confidenceSummary;
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

    public UUID getArtifactId() {
        return artifactId;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public ResearchGatePoint getGatePoint() {
        return gatePoint;
    }

    public RationaleEntryKind getKind() {
        return kind;
    }

    public RationaleEvidenceBasis getEvidenceBasis() {
        return evidenceBasis;
    }

    public RationaleProvenance getProvenance() {
        return provenance;
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public String getRationaleSummary() {
        return rationaleSummary;
    }

    public String getEvidenceLocator() {
        return evidenceLocator;
    }

    public String getConfidenceSummary() {
        return confidenceSummary;
    }

    public String getActor() {
        return actor;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
