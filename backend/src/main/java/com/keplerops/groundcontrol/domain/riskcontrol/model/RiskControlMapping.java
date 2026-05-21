package com.keplerops.groundcontrol.domain.riskcontrol.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Canonical mapping owner for GC-T003 Risk Scenario-Control Mapping.
 *
 * <p>Models a many-to-many relationship between a control endpoint (a catalog {@link Control}
 * OR a {@link ScopedControlImplementation}) and a risk endpoint ({@link RiskScenario} OR
 * {@link RiskRegisterRecord}), with optional asset/boundary context (C2), mapping-specific
 * objective/role/scope fields (C3), methodology-specific influence (C4), and mapping-owned
 * observations/evidence provenance (C8).
 *
 * <p>Exactly-one constraint on each side is enforced by DB CHECK constraints (see migration)
 * and by the service layer. The endpoint "type" is a computed property, not a persisted column.
 *
 * <p>Per ADR-052: this entity is the canonical GC-T003 mapping owner. {@link
 * com.keplerops.groundcontrol.domain.controls.model.ControlLink} and {@link
 * com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink} keep their generic
 * association role and are not repurposed for C5/C6 queries.
 */
@Entity
@Audited
@Table(
        name = "risk_control_mapping",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_rcm_control_scenario_asset",
                    columnNames = {"control_id", "risk_scenario_id", "operational_asset_id"}),
            @UniqueConstraint(
                    name = "uq_rcm_control_record_asset",
                    columnNames = {"control_id", "risk_register_record_id", "operational_asset_id"}),
            @UniqueConstraint(
                    name = "uq_rcm_scoped_scenario_asset",
                    columnNames = {"scoped_implementation_id", "risk_scenario_id", "operational_asset_id"}),
            @UniqueConstraint(
                    name = "uq_rcm_scoped_record_asset",
                    columnNames = {"scoped_implementation_id", "risk_register_record_id", "operational_asset_id"})
        })
public class RiskControlMapping extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // ---- Control-side endpoint (exactly one of control/scopedImplementation) ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_id")
    private Control control;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scoped_implementation_id")
    private ScopedControlImplementation scopedImplementation;

    // ---- Risk-side endpoint (exactly one of riskScenario/riskRegisterRecord) ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_scenario_id")
    private RiskScenario riskScenario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_register_record_id")
    private RiskRegisterRecord riskRegisterRecord;

    // ---- C2: Asset/operational-boundary context ----

    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operational_asset_id")
    private OperationalAsset operationalAsset;

    // ---- C3: Mapping-specific fields ----

    @Column(name = "mapping_objective", columnDefinition = "TEXT")
    private String mappingObjective;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_role", nullable = false, length = 20)
    private MappingControlRole controlRole;

    @Column(name = "mapping_scope", columnDefinition = "TEXT")
    private String mappingScope;

    // ---- C4: Methodology-specific influence ----

    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "methodology_profile_id")
    private MethodologyProfile methodologyProfile;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "methodology_influence", columnDefinition = "TEXT")
    private Map<String, Object> methodologyInfluence;

    // ---- C8: Mapping-owned observations (provenance edge) ----

    @NotAudited
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "mapping_observation",
            joinColumns = @JoinColumn(name = "risk_control_mapping_id"),
            inverseJoinColumns = @JoinColumn(name = "observation_id"))
    private Set<Observation> observations = new LinkedHashSet<>();

    // ---- C8: Mapping-owned evidence refs ----

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "mapping_evidence", joinColumns = @JoinColumn(name = "risk_control_mapping_id"))
    private List<MappingEvidenceRef> evidenceRefs = new ArrayList<>();

    protected RiskControlMapping() {
        // JPA
    }

    /**
     * Creates a mapping with a catalog control and a risk scenario.
     */
    public RiskControlMapping(
            Project project, Control control, RiskScenario riskScenario, MappingControlRole controlRole) {
        this.project = project;
        this.control = control;
        this.riskScenario = riskScenario;
        this.controlRole = controlRole;
    }

    /**
     * Creates a mapping with a catalog control and a risk register record.
     */
    public RiskControlMapping(
            Project project,
            Control control,
            RiskRegisterRecord riskRegisterRecord,
            MappingControlRole controlRole,
            boolean recordSide) {
        this.project = project;
        this.control = control;
        this.riskRegisterRecord = riskRegisterRecord;
        this.controlRole = controlRole;
    }

    /**
     * Creates a mapping with a scoped implementation and a risk scenario.
     */
    public RiskControlMapping(
            Project project,
            ScopedControlImplementation scopedImplementation,
            RiskScenario riskScenario,
            MappingControlRole controlRole,
            boolean scopedSide) {
        this.project = project;
        this.scopedImplementation = scopedImplementation;
        this.riskScenario = riskScenario;
        this.controlRole = controlRole;
    }

    /**
     * Creates a mapping with a scoped implementation and a risk register record.
     */
    public RiskControlMapping(
            Project project,
            ScopedControlImplementation scopedImplementation,
            RiskRegisterRecord riskRegisterRecord,
            MappingControlRole controlRole) {
        this.project = project;
        this.scopedImplementation = scopedImplementation;
        this.riskRegisterRecord = riskRegisterRecord;
        this.controlRole = controlRole;
    }

    // ---- Control-side endpoint type (computed) ----

    public boolean isControlSide() {
        return control != null;
    }

    public boolean isScopedImplementationSide() {
        return scopedImplementation != null;
    }

    // ---- Risk-side endpoint type (computed) ----

    public boolean isScenarioSide() {
        return riskScenario != null;
    }

    public boolean isRegisterRecordSide() {
        return riskRegisterRecord != null;
    }

    // ---- Getters ----

    public Project getProject() {
        return project;
    }

    public Control getControl() {
        return control;
    }

    public ScopedControlImplementation getScopedImplementation() {
        return scopedImplementation;
    }

    public RiskScenario getRiskScenario() {
        return riskScenario;
    }

    public RiskRegisterRecord getRiskRegisterRecord() {
        return riskRegisterRecord;
    }

    public OperationalAsset getOperationalAsset() {
        return operationalAsset;
    }

    public void setOperationalAsset(OperationalAsset operationalAsset) {
        this.operationalAsset = operationalAsset;
    }

    public String getMappingObjective() {
        return mappingObjective;
    }

    public void setMappingObjective(String mappingObjective) {
        this.mappingObjective = mappingObjective;
    }

    public MappingControlRole getControlRole() {
        return controlRole;
    }

    public void setControlRole(MappingControlRole controlRole) {
        this.controlRole = controlRole;
    }

    public String getMappingScope() {
        return mappingScope;
    }

    public void setMappingScope(String mappingScope) {
        this.mappingScope = mappingScope;
    }

    public MethodologyProfile getMethodologyProfile() {
        return methodologyProfile;
    }

    public void setMethodologyProfile(MethodologyProfile methodologyProfile) {
        this.methodologyProfile = methodologyProfile;
    }

    public Map<String, Object> getMethodologyInfluence() {
        return methodologyInfluence;
    }

    public void setMethodologyInfluence(Map<String, Object> methodologyInfluence) {
        this.methodologyInfluence = methodologyInfluence;
    }

    public Set<Observation> getObservations() {
        return observations;
    }

    public void addObservation(Observation observation) {
        this.observations.add(observation);
    }

    public void removeObservation(Observation observation) {
        this.observations.remove(observation);
    }

    public List<MappingEvidenceRef> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void addEvidenceRef(MappingEvidenceRef ref) {
        this.evidenceRefs.add(ref);
    }

    public void removeEvidenceRef(MappingEvidenceRef ref) {
        this.evidenceRefs.remove(ref);
    }
}
