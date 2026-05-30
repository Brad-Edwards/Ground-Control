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

    @Convert(converter = JacksonTextCollectionConverters.ActionItemListConverter.class)
    @Column(name = "action_items", columnDefinition = "TEXT")
    private List<ActionItem> actionItems;

    @Convert(converter = JacksonTextCollectionConverters.ReassessmentTriggerListConverter.class)
    @Column(name = "reassessment_triggers", columnDefinition = "TEXT")
    private List<ReassessmentTrigger> reassessmentTriggers;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "methodology_profile_id")
    private MethodologyProfile methodologyProfile;

    @Column(name = "methodology_strategy_key", length = 100)
    private String methodologyStrategyKey;

    /**
     * GC-T015: optional FK to the originating risk assessment result.
     * Project scope is validated through {@code GraphTargetResolverService} —
     * a result from another project is rejected at the service boundary.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "risk_assessment_result_id")
    private RiskAssessmentResult riskAssessmentResult;

    /**
     * GC-T015: typed list of monitored risk factors. Each entry names a factor,
     * the change category it belongs to (NIST §3.4), and the cadence at which
     * the factor is re-checked.
     */
    @Convert(converter = JacksonTextCollectionConverters.MonitoredRiskFactorListConverter.class)
    @Column(name = "monitored_risk_factors", columnDefinition = "TEXT")
    private List<MonitoredRiskFactor> monitoredRiskFactors;

    /**
     * GC-T015: ISO-8601 duration (e.g. {@code P30D}) at which the treatment
     * plan as a whole is re-checked. Separate from per-factor cadence inside
     * {@link MonitoredRiskFactor}; the plan-level cadence is the lower-bound
     * heartbeat used by downstream automation.
     */
    @Column(name = "update_cadence", length = 50)
    private String updateCadence;

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

    public List<ActionItem> getActionItems() {
        return actionItems;
    }

    public void setActionItems(List<ActionItem> actionItems) {
        this.actionItems = actionItems;
    }

    public List<ReassessmentTrigger> getReassessmentTriggers() {
        return reassessmentTriggers;
    }

    public void setReassessmentTriggers(List<ReassessmentTrigger> reassessmentTriggers) {
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

    public RiskAssessmentResult getRiskAssessmentResult() {
        return riskAssessmentResult;
    }

    public void setRiskAssessmentResult(RiskAssessmentResult riskAssessmentResult) {
        this.riskAssessmentResult = riskAssessmentResult;
    }

    public List<MonitoredRiskFactor> getMonitoredRiskFactors() {
        return monitoredRiskFactors;
    }

    public void setMonitoredRiskFactors(List<MonitoredRiskFactor> monitoredRiskFactors) {
        this.monitoredRiskFactors = monitoredRiskFactors;
    }

    public String getUpdateCadence() {
        return updateCadence;
    }

    public void setUpdateCadence(String updateCadence) {
        this.updateCadence = updateCadence;
    }
}
