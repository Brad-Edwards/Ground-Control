package com.keplerops.groundcontrol.domain.controls.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
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
import java.time.LocalDate;
import java.util.List;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Control effectiveness assessment per GC-I013.
 *
 * <p>A durable, audited row that records design and operating effectiveness ratings for a {@link
 * Control} at a point in time. Design effectiveness evaluates whether the control is designed to
 * address its risk; operating effectiveness evaluates whether observed operation shows the control
 * works as designed. The two ratings are stored as separate fields because a control can be
 * well-designed but poorly operated, or vice versa (SOC 2 Type II / SOX testing convention).
 *
 * <p>{@code operatingEffectiveness} is the stable, audited, project-scoped input that future
 * GC-T003 risk-scoring code consumes. This entity does not perform that scoring; it only exposes
 * the rating. See {@code architecture/notes/control-testing-entity-preflight.md} for the seam.
 *
 * <p>{@code assessor} is a domain-provenance field; it does not replace the authenticated audit
 * actor on the revision record.
 */
@Entity
@Audited
@Table(
        name = "control_effectiveness_assessment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class ControlEffectivenessAssessment extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // Control is itself @Audited, so this relation is audited normally; the audit table carries
    // control_id so revision history can resolve which control an old assessment referenced.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "control_id", nullable = false)
    private Control control;

    @Column(nullable = false, length = 50)
    private String uid;

    @Enumerated(EnumType.STRING)
    @Column(name = "design_effectiveness", nullable = false, length = 30)
    private ControlEffectivenessRating designEffectiveness;

    @Enumerated(EnumType.STRING)
    @Column(name = "operating_effectiveness", nullable = false, length = 30)
    private ControlEffectivenessRating operatingEffectiveness;

    @Column(name = "assessed_at", nullable = false)
    private LocalDate assessedAt;

    @Column(nullable = false, length = 200)
    private String assessor;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * IDs of {@code ControlTest} rows this assessment consumed as supporting evidence for its
     * operating-effectiveness judgment. UUIDs are stored as strings to keep the column shape
     * uniform with other JSON list columns in the repo (see {@code StringListConverter}). The
     * service validates each ID resolves to a real ControlTest in the same project; the graph
     * projection emits one {@code SUPPORTED_BY} edge per ID.
     */
    @Convert(converter = JacksonTextCollectionConverters.StringListConverter.class)
    @Column(name = "supporting_test_ids", columnDefinition = "TEXT")
    private List<String> supportingTestIds;

    /**
     * Optional FAIR-CAM domain attribution per GC-I017. Identifies the FAIR-CAM
     * functional domain this assessment belongs to (loss-event, variance-management,
     * or decision-support). Nullable for non-FAIR programs and for legacy
     * assessments predating GC-I017; FAIR-CAM analytics scope by this attribute
     * and emit a per-assessment limitation when it is missing.
     *
     * <p>This is intentionally orthogonal to {@link #operatingEffectiveness} /
     * {@link #designEffectiveness} — FAIR-CAM analytics never collapse the
     * three dimensions (capability, coverage, operational performance) into a
     * single effectiveness rating; the rating remains a separate, narrower
     * label.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "fair_cam_control_domain", length = 40)
    private FairCamControlDomain fairCamControlDomain;

    protected ControlEffectivenessAssessment() {
        // JPA
    }

    public ControlEffectivenessAssessment(
            Project project,
            Control control,
            String uid,
            ControlEffectivenessRating designEffectiveness,
            ControlEffectivenessRating operatingEffectiveness,
            LocalDate assessedAt,
            String assessor) {
        this.project = project;
        this.control = control;
        this.uid = uid;
        this.designEffectiveness = designEffectiveness;
        this.operatingEffectiveness = operatingEffectiveness;
        this.assessedAt = assessedAt;
        this.assessor = assessor;
    }

    public Project getProject() {
        return project;
    }

    public Control getControl() {
        return control;
    }

    public String getUid() {
        return uid;
    }

    public ControlEffectivenessRating getDesignEffectiveness() {
        return designEffectiveness;
    }

    public void setDesignEffectiveness(ControlEffectivenessRating designEffectiveness) {
        this.designEffectiveness = designEffectiveness;
    }

    public ControlEffectivenessRating getOperatingEffectiveness() {
        return operatingEffectiveness;
    }

    public void setOperatingEffectiveness(ControlEffectivenessRating operatingEffectiveness) {
        this.operatingEffectiveness = operatingEffectiveness;
    }

    public LocalDate getAssessedAt() {
        return assessedAt;
    }

    public void setAssessedAt(LocalDate assessedAt) {
        this.assessedAt = assessedAt;
    }

    public String getAssessor() {
        return assessor;
    }

    public void setAssessor(String assessor) {
        this.assessor = assessor;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getSupportingTestIds() {
        return supportingTestIds;
    }

    public void setSupportingTestIds(List<String> supportingTestIds) {
        this.supportingTestIds = supportingTestIds;
    }

    public FairCamControlDomain getFairCamControlDomain() {
        return fairCamControlDomain;
    }

    public void setFairCamControlDomain(FairCamControlDomain fairCamControlDomain) {
        this.fairCamControlDomain = fairCamControlDomain;
    }
}
