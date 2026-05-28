package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * A scoped statement of potential future loss tied to one or more affected
 * operational assets, boundaries, processes, systems, objectives, or third
 * parties within a defined time horizon. Anchors risk to scenario rather
 * than a vague label, supporting FAIR-CRST, NIST SP 800-30, and ISO-style
 * risk methods.
 *
 * FAIR-CRST scoping axes: threat, asset, method, effect, timeHorizon.
 */
@Entity
@Audited
@Table(name = "risk_scenario", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class RiskScenario extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 20)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskScenarioStatus status = RiskScenarioStatus.DRAFT;

    @Column(name = "threat", nullable = false, columnDefinition = "TEXT")
    private String threat;

    @Column(name = "method", nullable = false, columnDefinition = "TEXT")
    private String method;

    @Column(name = "asset", nullable = false, columnDefinition = "TEXT")
    private String asset;

    @Column(name = "effect", nullable = false, columnDefinition = "TEXT")
    private String effect;

    @Column(name = "time_horizon", nullable = false, length = 100)
    private String timeHorizon;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected RiskScenario() {
        // JPA
    }

    public RiskScenario(
            Project project, String uid, String title, String threat, String method, String asset, String effect) {
        this.project = project;
        this.uid = uid;
        this.title = title;
        this.threat = threat;
        this.method = method;
        this.asset = asset;
        this.effect = effect;
    }

    public void transitionStatus(RiskScenarioStatus newStatus) {
        if (newStatus == null) {
            throw new DomainValidationException("Target status must not be null");
        }
        if (!this.status.canTransitionTo(newStatus)) {
            throw new DomainValidationException(
                    "Cannot transition from " + this.status + " to " + newStatus,
                    "validation_error",
                    Map.of(
                            "current_status",
                            this.status.name(),
                            "target_status",
                            newStatus.name(),
                            "valid_targets",
                            this.status.validTargets().toString()));
        }
        this.status = newStatus;
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

    public void setTitle(String title) {
        this.title = title;
    }

    public RiskScenarioStatus getStatus() {
        return status;
    }

    public String getThreat() {
        return threat;
    }

    public void setThreat(String threat) {
        this.threat = threat;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getAsset() {
        return asset;
    }

    public void setAsset(String asset) {
        this.asset = asset;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public String getTimeHorizon() {
        return timeHorizon;
    }

    public void setTimeHorizon(String timeHorizon) {
        this.timeHorizon = timeHorizon;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getFairSentence() {
        return threat + " impacts " + asset + " via " + method + ", causing " + effect;
    }

    @Override
    public String toString() {
        return uid + ": " + title;
    }
}
