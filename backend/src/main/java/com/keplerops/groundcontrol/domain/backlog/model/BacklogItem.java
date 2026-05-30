package com.keplerops.groundcontrol.domain.backlog.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.backlog.state.BacklogItemStatus;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
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
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Product backlog item with calibrated Cost-of-Delay components per GC-W003.
 *
 * <p>Each backlog item carries four probability-distribution-valued
 * components: user-business value, time criticality, risk reduction or
 * opportunity enablement, and job duration. WSJF is computed as a
 * distribution per item; the result is not persisted on the entity itself —
 * the analysis service runs Monte Carlo on demand from the current calibrated
 * inputs so a re-prioritization analysis always reflects the latest estimates.
 *
 * <p>Encodes commercial strategy: full @Audited treatment, project-scoped
 * access per the cluster security note.
 */
@Entity
@Audited
@Table(name = "backlog_item", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class BacklogItem extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 30)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BacklogItemStatus status = BacklogItemStatus.CANDIDATE;

    @Column(name = "user_business_value", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.CostOfDelayComponentConverter.class)
    private CostOfDelayComponent userBusinessValue;

    @Column(name = "time_criticality", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.CostOfDelayComponentConverter.class)
    private CostOfDelayComponent timeCriticality;

    @Column(name = "risk_reduction_opportunity_enablement", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.CostOfDelayComponentConverter.class)
    private CostOfDelayComponent riskReductionOpportunityEnablement;

    @Column(name = "job_duration", columnDefinition = "TEXT")
    @Convert(converter = JacksonTextCollectionConverters.CostOfDelayComponentConverter.class)
    private CostOfDelayComponent jobDuration;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected BacklogItem() {
        // JPA
    }

    public BacklogItem(Project project, String uid, String title) {
        if (project == null) {
            throw new DomainValidationException("project must not be null");
        }
        if (uid == null || uid.isBlank()) {
            throw new DomainValidationException("uid must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new DomainValidationException("title must not be blank");
        }
        this.project = project;
        this.uid = uid;
        this.title = title;
    }

    public void transitionStatus(BacklogItemStatus target) {
        if (target == null) {
            throw new DomainValidationException("Target status must not be null");
        }
        if (target == BacklogItemStatus.READY && !hasAllComponents()) {
            throw new DomainValidationException(
                    "Cannot mark backlog item READY without calibrated CoD and duration components",
                    "validation_error",
                    Map.of(
                            "uid", uid,
                            "userBusinessValue", String.valueOf(userBusinessValue != null),
                            "timeCriticality", String.valueOf(timeCriticality != null),
                            "riskReductionOpportunityEnablement",
                                    String.valueOf(riskReductionOpportunityEnablement != null),
                            "jobDuration", String.valueOf(jobDuration != null)));
        }
        if (!this.status.canTransitionTo(target)) {
            throw new DomainValidationException(
                    "Cannot transition from " + this.status + " to " + target,
                    "validation_error",
                    Map.of(
                            "current_status", this.status.name(),
                            "target_status", target.name(),
                            "valid_targets", this.status.validTargets().toString()));
        }
        this.status = target;
    }

    public boolean hasAllComponents() {
        return userBusinessValue != null
                && timeCriticality != null
                && riskReductionOpportunityEnablement != null
                && jobDuration != null;
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
        if (title == null || title.isBlank()) {
            throw new DomainValidationException("title must not be blank");
        }
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BacklogItemStatus getStatus() {
        return status;
    }

    public CostOfDelayComponent getUserBusinessValue() {
        return userBusinessValue;
    }

    public void setUserBusinessValue(CostOfDelayComponent userBusinessValue) {
        this.userBusinessValue = userBusinessValue;
    }

    public CostOfDelayComponent getTimeCriticality() {
        return timeCriticality;
    }

    public void setTimeCriticality(CostOfDelayComponent timeCriticality) {
        this.timeCriticality = timeCriticality;
    }

    public CostOfDelayComponent getRiskReductionOpportunityEnablement() {
        return riskReductionOpportunityEnablement;
    }

    public void setRiskReductionOpportunityEnablement(CostOfDelayComponent v) {
        this.riskReductionOpportunityEnablement = v;
    }

    public CostOfDelayComponent getJobDuration() {
        return jobDuration;
    }

    public void setJobDuration(CostOfDelayComponent jobDuration) {
        this.jobDuration = jobDuration;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public String toString() {
        return uid + ": " + title;
    }
}
