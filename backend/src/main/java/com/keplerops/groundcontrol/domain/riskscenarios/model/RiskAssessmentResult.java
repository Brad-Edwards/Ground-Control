package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "risk_assessment_result")
public class RiskAssessmentResult extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "risk_scenario_id", nullable = false)
    private RiskScenario riskScenario;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "methodology_profile_id", nullable = false)
    private MethodologyProfile methodologyProfile;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "risk_register_record_id")
    private RiskRegisterRecord riskRegisterRecord;

    @Column(name = "analyst_identity", length = 200)
    private String analystIdentity;

    @Column(columnDefinition = "TEXT")
    private String assumptions;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "input_factors", columnDefinition = "TEXT")
    private Map<String, Object> inputFactors;

    @Column(name = "observation_date")
    private Instant observationDate;

    @Column(name = "assessment_at")
    private Instant assessmentAt;

    @Column(name = "time_horizon", length = 100)
    private String timeHorizon;

    @Column(length = 50)
    private String confidence;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "uncertainty_metadata", columnDefinition = "TEXT")
    private Map<String, Object> uncertaintyMetadata;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "computed_outputs", columnDefinition = "TEXT")
    private Map<String, Object> computedOutputs;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_state", nullable = false, length = 20)
    private RiskAssessmentApprovalStatus approvalState = RiskAssessmentApprovalStatus.DRAFT;

    @Convert(converter = JacksonTextCollectionConverters.StringListConverter.class)
    @Column(name = "evidence_refs", columnDefinition = "TEXT")
    private java.util.List<String> evidenceRefs;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Reassessment-needed timestamp set by the C8 listener
     * ({@code ReassessmentSignalService}) when an upstream treatment, asset,
     * or control change implicates this assessment row. Read-only signal —
     * the row is otherwise unchanged. Null means no outstanding signal.
     */
    @Column(name = "reassessment_required_at")
    private Instant reassessmentRequiredAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "risk_assessment_result_observation",
            joinColumns = @JoinColumn(name = "risk_assessment_result_id"),
            inverseJoinColumns = @JoinColumn(name = "observation_id"))
    private Set<Observation> observations = new LinkedHashSet<>();

    protected RiskAssessmentResult() {
        // JPA
    }

    public RiskAssessmentResult(Project project, RiskScenario riskScenario, MethodologyProfile methodologyProfile) {
        this.project = project;
        this.riskScenario = riskScenario;
        this.methodologyProfile = methodologyProfile;
    }

    public void transitionApprovalState(RiskAssessmentApprovalStatus newState) {
        if (newState == null || !approvalState.canTransitionTo(newState)) {
            throw new DomainValidationException(
                    "Cannot transition approval state from " + approvalState + " to " + newState);
        }
        this.approvalState = newState;
    }

    public void replaceObservations(java.util.Collection<Observation> observations) {
        this.observations.clear();
        this.observations.addAll(observations);
    }

    public Project getProject() {
        return project;
    }

    public RiskScenario getRiskScenario() {
        return riskScenario;
    }

    public MethodologyProfile getMethodologyProfile() {
        return methodologyProfile;
    }

    public void setMethodologyProfile(MethodologyProfile methodologyProfile) {
        this.methodologyProfile = methodologyProfile;
    }

    public RiskRegisterRecord getRiskRegisterRecord() {
        return riskRegisterRecord;
    }

    public void setRiskRegisterRecord(RiskRegisterRecord riskRegisterRecord) {
        this.riskRegisterRecord = riskRegisterRecord;
    }

    public String getAnalystIdentity() {
        return analystIdentity;
    }

    public void setAnalystIdentity(String analystIdentity) {
        this.analystIdentity = analystIdentity;
    }

    public String getAssumptions() {
        return assumptions;
    }

    public void setAssumptions(String assumptions) {
        this.assumptions = assumptions;
    }

    public Map<String, Object> getInputFactors() {
        return inputFactors;
    }

    public void setInputFactors(Map<String, Object> inputFactors) {
        this.inputFactors = inputFactors;
    }

    public Instant getObservationDate() {
        return observationDate;
    }

    public void setObservationDate(Instant observationDate) {
        this.observationDate = observationDate;
    }

    public Instant getAssessmentAt() {
        return assessmentAt;
    }

    public void setAssessmentAt(Instant assessmentAt) {
        this.assessmentAt = assessmentAt;
    }

    public String getTimeHorizon() {
        return timeHorizon;
    }

    public void setTimeHorizon(String timeHorizon) {
        this.timeHorizon = timeHorizon;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public Map<String, Object> getUncertaintyMetadata() {
        return uncertaintyMetadata;
    }

    public void setUncertaintyMetadata(Map<String, Object> uncertaintyMetadata) {
        this.uncertaintyMetadata = uncertaintyMetadata;
    }

    public Map<String, Object> getComputedOutputs() {
        return computedOutputs;
    }

    public void setComputedOutputs(Map<String, Object> computedOutputs) {
        this.computedOutputs = computedOutputs;
    }

    public RiskAssessmentApprovalStatus getApprovalState() {
        return approvalState;
    }

    public java.util.List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(java.util.List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Set<Observation> getObservations() {
        return observations;
    }

    public Instant getReassessmentRequiredAt() {
        return reassessmentRequiredAt;
    }

    public void setReassessmentRequiredAt(Instant reassessmentRequiredAt) {
        this.reassessmentRequiredAt = reassessmentRequiredAt;
    }
}
