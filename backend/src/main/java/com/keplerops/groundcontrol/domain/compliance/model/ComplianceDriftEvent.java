package com.keplerops.groundcontrol.domain.compliance.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftSeverity;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Append-only drift event row published by
 * {@code ComplianceDriftDetectorService} when a synchronous control /
 * evidence / code-change signal indicates a compliance-posture shift
 * (GC-I004).
 *
 * <p>Posture itself is a read projection over these events plus the existing
 * compliance-framework-mapping aggregate (cluster 4 consumer); this entity is
 * the durable signal stream, not a posture cache. The only post-create
 * mutation is the one-shot {@code acknowledgedAt}/{@code acknowledgedBy}
 * write — drift events are never re-categorized, deleted, or rewritten;
 * resolutions are published as new rows of category
 * {@link ComplianceDriftCategory#RESOLUTION}.
 */
@Entity
@Audited
@Table(name = "compliance_drift_event")
public class ComplianceDriftEvent extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ComplianceDriftCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ComplianceDriftSeverity severity;

    @Column(name = "source_entity_type", nullable = false, length = 60)
    private String sourceEntityType;

    @Column(name = "source_entity_id", nullable = false)
    private UUID sourceEntityId;

    @Column(name = "affected_entity_type", length = 60)
    private String affectedEntityType;

    @Column(name = "affected_entity_id")
    private UUID affectedEntityId;

    @Column(nullable = false, length = 1000)
    private String summary;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "detected_by", length = 200)
    private String detectedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by", length = 200)
    private String acknowledgedBy;

    protected ComplianceDriftEvent() {
        // JPA
    }

    public ComplianceDriftEvent(
            Project project,
            ComplianceDriftCategory category,
            ComplianceDriftSeverity severity,
            String sourceEntityType,
            UUID sourceEntityId,
            String summary,
            Instant detectedAt) {
        this.project = project;
        this.category = category;
        this.severity = severity;
        this.sourceEntityType = sourceEntityType;
        this.sourceEntityId = sourceEntityId;
        this.summary = summary;
        this.detectedAt = detectedAt;
    }

    public Project getProject() {
        return project;
    }

    public ComplianceDriftCategory getCategory() {
        return category;
    }

    public ComplianceDriftSeverity getSeverity() {
        return severity;
    }

    public String getSourceEntityType() {
        return sourceEntityType;
    }

    public UUID getSourceEntityId() {
        return sourceEntityId;
    }

    public String getAffectedEntityType() {
        return affectedEntityType;
    }

    public void setAffectedEntityType(String affectedEntityType) {
        this.affectedEntityType = affectedEntityType;
    }

    public UUID getAffectedEntityId() {
        return affectedEntityId;
    }

    public void setAffectedEntityId(UUID affectedEntityId) {
        this.affectedEntityId = affectedEntityId;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public String getDetectedBy() {
        return detectedBy;
    }

    public void setDetectedBy(String detectedBy) {
        this.detectedBy = detectedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    /**
     * One-shot acknowledgement write. The drift detector service is the only
     * caller and enforces single-shot semantics; the DB has no shape-level
     * guard.
     */
    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(String acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }
}
