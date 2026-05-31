package com.keplerops.groundcontrol.domain.evidence.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.EvidenceSourceRefListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * First-class summarized-evidence aggregate per GC-M016 / ADR-045.
 *
 * <p>An {@code EvidenceArtifact} is the durable record produced by deriving a
 * conclusion (rollup, assurance judgment, summary) from one or more sources:
 * observations, control tests, control effectiveness assessments,
 * verification results, risk assessment results, findings, signed
 * attestations, or external references. The entity is project-scoped through
 * {@link Project} (audited as {@code @NotAudited} per ADR-038), carries its
 * own derivation timestamp and method, and never overwrites prior state — the
 * only post-create mutation is {@code supersededByArtifactId}, which is set
 * exactly once when a newer artifact replaces this one.
 *
 * <p>Distinct aggregate from {@code Observation} (raw state fact),
 * {@code ControlTest} (per-execution evidence), {@code
 * ControlEffectivenessAssessment} (per-control assurance rating),
 * {@code VerificationResult} (prover-output evidence), and {@code Finding}
 * (governed deficiency); those are sources, not fields of this entity.
 */
@Entity
@Audited
@Table(name = "evidence_artifact", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class EvidenceArtifact extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 40)
    private EvidenceType evidenceType;

    @Column(name = "derivation_method", nullable = false, length = 200)
    private String derivationMethod;

    @Column(name = "derived_at", nullable = false)
    private Instant derivedAt;

    @Column(name = "derived_by", length = 200)
    private String derivedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "assurance_level", length = 10)
    private AssuranceLevel assuranceLevel;

    @Column(length = 50)
    private String confidence;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Set exactly once when a newer {@link EvidenceArtifact} supersedes this row.
     * Service-layer guards refuse a second write; {@link
     * com.keplerops.groundcontrol.domain.evidence.service.EvidenceArtifactService}
     * is the only writer (no PUT/DELETE controller surface).
     */
    @Column(name = "superseded_by_artifact_id")
    private UUID supersededByArtifactId;

    @Column(name = "sources", columnDefinition = "TEXT")
    @Convert(converter = EvidenceSourceRefListConverter.class)
    private List<EvidenceSourceRef> sources;

    /**
     * Optional point-in-time at which this artifact's assurance lapses
     * (GC-I004 / ADR-045 §8). Setting {@code expiresAt} does NOT mutate the
     * artifact after that instant — append-only is preserved; expiration is a
     * derived current-state flag, never an overwrite. The scheduled sweep job
     * publishes an expiry event once per artifact at the first sweep after
     * {@code expiresAt}; replacing an expired artifact still goes through the
     * supersede chain.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Convenience metadata: how many days the artifact's validity covers,
     * relative to {@code derivedAt}. Optional and informational only; the
     * sweep job decides expiration purely from {@code expiresAt}. Kept as a
     * column (not derived) so callers can express &quot;90 days&quot; without
     * recomputing the derived instant.
     */
    @Column(name = "validity_window_days")
    private Integer validityWindowDays;

    protected EvidenceArtifact() {
        // JPA
    }

    public EvidenceArtifact(
            Project project,
            String uid,
            String title,
            String summary,
            EvidenceType evidenceType,
            String derivationMethod,
            Instant derivedAt) {
        this.project = project;
        this.uid = uid;
        this.title = title;
        this.summary = summary;
        this.evidenceType = evidenceType;
        this.derivationMethod = derivationMethod;
        this.derivedAt = derivedAt;
    }

    public Project getProject() {
        return project;
    }

    public String getUid() {
        return uid;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public String getDerivationMethod() {
        return derivationMethod;
    }

    public Instant getDerivedAt() {
        return derivedAt;
    }

    public String getDerivedBy() {
        return derivedBy;
    }

    public void setDerivedBy(String derivedBy) {
        this.derivedBy = derivedBy;
    }

    public AssuranceLevel getAssuranceLevel() {
        return assuranceLevel;
    }

    public void setAssuranceLevel(AssuranceLevel assuranceLevel) {
        this.assuranceLevel = assuranceLevel;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public UUID getSupersededByArtifactId() {
        return supersededByArtifactId;
    }

    /**
     * Mark this artifact as superseded by another. Service-layer logic refuses
     * a second call; the database has no shape-level guard here so the service
     * is the single enforcement point per ADR-045.
     */
    public void setSupersededByArtifactId(UUID supersededByArtifactId) {
        this.supersededByArtifactId = supersededByArtifactId;
    }

    public List<EvidenceSourceRef> getSources() {
        return sources;
    }

    public void setSources(List<EvidenceSourceRef> sources) {
        this.sources = sources;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getValidityWindowDays() {
        return validityWindowDays;
    }

    public void setValidityWindowDays(Integer validityWindowDays) {
        this.validityWindowDays = validityWindowDays;
    }

    /**
     * Append-only currentness check (GC-I004 / ADR-045 §8). True when
     * {@code expiresAt} has been set and is not after {@code asOf}. Returns
     * false when {@code expiresAt} is null (no expiration declared) — those
     * artifacts remain current forever absent supersede.
     */
    public boolean isExpiredAt(Instant asOf) {
        return expiresAt != null && !asOf.isBefore(expiresAt);
    }
}
