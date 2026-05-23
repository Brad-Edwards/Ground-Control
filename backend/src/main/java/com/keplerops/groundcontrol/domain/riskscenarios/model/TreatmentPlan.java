package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "treatment_plan", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class TreatmentPlan extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "risk_register_record_id", nullable = false)
    private RiskRegisterRecord riskRegisterRecord;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "risk_scenario_id")
    private RiskScenario riskScenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TreatmentStrategy strategy;

    @Column(length = 200)
    private String owner;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "due_date")
    private Instant dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TreatmentPlanStatus status = TreatmentPlanStatus.PLANNED;

    @Convert(converter = JacksonTextCollectionConverters.MapListConverter.class)
    @Column(name = "action_items", columnDefinition = "TEXT")
    private List<Map<String, Object>> actionItems;

    @Convert(converter = JacksonTextCollectionConverters.StringListConverter.class)
    @Column(name = "reassessment_triggers", columnDefinition = "TEXT")
    private List<String> reassessmentTriggers;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "methodology_profile_id")
    private MethodologyProfile methodologyProfile;

    @Column(name = "methodology_strategy_key", length = 100)
    private String methodologyStrategyKey;

    protected TreatmentPlan() {
        // JPA
    }

    public TreatmentPlan(
            Project project,
            String uid,
            String title,
            RiskRegisterRecord riskRegisterRecord,
            TreatmentStrategy strategy) {
        this.project = project;
        this.uid = uid;
        this.title = title;
        this.riskRegisterRecord = riskRegisterRecord;
        this.strategy = strategy;
    }

    public void transitionStatus(TreatmentPlanStatus newStatus) {
        if (newStatus == null || !status.canTransitionTo(newStatus)) {
            throw new DomainValidationException("Cannot transition treatment plan from " + status + " to " + newStatus);
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

    public RiskRegisterRecord getRiskRegisterRecord() {
        return riskRegisterRecord;
    }

    public RiskScenario getRiskScenario() {
        return riskScenario;
    }

    public void setRiskScenario(RiskScenario riskScenario) {
        this.riskScenario = riskScenario;
    }

    public TreatmentStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(TreatmentStrategy strategy) {
        this.strategy = strategy;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public Instant getDueDate() {
        return dueDate;
    }

    public void setDueDate(Instant dueDate) {
        this.dueDate = dueDate;
    }

    public TreatmentPlanStatus getStatus() {
        return status;
    }

    public List<Map<String, Object>> getActionItems() {
        return actionItems;
    }

    public void setActionItems(List<Map<String, Object>> actionItems) {
        this.actionItems = actionItems;
    }

    public List<String> getReassessmentTriggers() {
        return reassessmentTriggers;
    }

    public void setReassessmentTriggers(List<String> reassessmentTriggers) {
        this.reassessmentTriggers = reassessmentTriggers;
    }

    public MethodologyProfile getMethodologyProfile() {
        return methodologyProfile;
    }

    public void setMethodologyProfile(MethodologyProfile methodologyProfile) {
        this.methodologyProfile = methodologyProfile;
    }

    public String getMethodologyStrategyKey() {
        return methodologyStrategyKey;
    }

    public void setMethodologyStrategyKey(String methodologyStrategyKey) {
        this.methodologyStrategyKey = methodologyStrategyKey;
    }
}
