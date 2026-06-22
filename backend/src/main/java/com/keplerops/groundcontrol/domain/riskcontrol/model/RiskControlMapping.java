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
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Canonical mapping owner for GC-T003 Risk Scenario-Control Mapping, extended by GC-H006
 * to accept a {@link ThreatModel} as a third analysis-side endpoint.
 *
 * <p>Models a many-to-many relationship between a control endpoint (a catalog {@link Control}
 * OR a {@link ScopedControlImplementation}) and an analysis endpoint ({@link RiskScenario} OR
 * {@link RiskRegisterRecord} OR {@link ThreatModel}), with optional asset/boundary context (C2),
 * mapping-specific objective/role/scope fields (C3), methodology-specific influence (C4), and
 * mapping-owned observations/evidence provenance (C8).
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
// Per-endpoint-family uniqueness (one mapping per control endpoint × analysis endpoint × asset)
// is enforced by PARTIAL unique indexes in the Flyway migrations (V137), not by JPA
// @UniqueConstraint: a plain unique constraint over the nullable polymorphic endpoint columns
// would treat rows of a different endpoint family (whose columns are NULL) as colliding under
// NULLS NOT DISTINCT. Partial uniqueness is not expressible via JPA, so the migration owns it.
@Table(name = "risk_control_mapping")
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

    // ---- Analysis-side endpoint (exactly one of threatModel/riskScenario/riskRegisterRecord) ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "threat_model_id")
    private ThreatModel threatModel;

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

    /** Creates a mapping with a catalog control and a risk scenario. */
    public static RiskControlMapping forControlScenario(
            Project project, Control control, RiskScenario riskScenario, MappingControlRole controlRole) {
        RiskControlMapping m = new RiskControlMapping();
        m.project = project;
        m.control = control;
        m.riskScenario = riskScenario;
        m.controlRole = controlRole;
        return m;
    }

    /** Creates a mapping with a catalog control and a risk register record. */
    public static RiskControlMapping forControlRecord(
            Project project, Control control, RiskRegisterRecord riskRegisterRecord, MappingControlRole controlRole) {
        RiskControlMapping m = new RiskControlMapping();
        m.project = project;
        m.control = control;
        m.riskRegisterRecord = riskRegisterRecord;
        m.controlRole = controlRole;
        return m;
    }

    /** Creates a mapping with a scoped implementation and a risk scenario. */
    public static RiskControlMapping forScopedScenario(
            Project project,
            ScopedControlImplementation scopedImplementation,
            RiskScenario riskScenario,
            MappingControlRole controlRole) {
        RiskControlMapping m = new RiskControlMapping();
        m.project = project;
        m.scopedImplementation = scopedImplementation;
        m.riskScenario = riskScenario;
        m.controlRole = controlRole;
        return m;
    }

    /** Creates a mapping with a scoped implementation and a risk register record. */
    public static RiskControlMapping forScopedRecord(
            Project project,
            ScopedControlImplementation scopedImplementation,
            RiskRegisterRecord riskRegisterRecord,
            MappingControlRole controlRole) {
        RiskControlMapping m = new RiskControlMapping();
        m.project = project;
        m.scopedImplementation = scopedImplementation;
        m.riskRegisterRecord = riskRegisterRecord;
        m.controlRole = controlRole;
        return m;
    }

    /** Creates a mapping with a catalog control and a threat model entry (GC-H006). */
    public static RiskControlMapping forControlThreat(
            Project project, Control control, ThreatModel threatModel, MappingControlRole controlRole) {
        RiskControlMapping m = new RiskControlMapping();
        m.project = project;
        m.control = control;
        m.threatModel = threatModel;
        m.controlRole = controlRole;
        return m;
    }

    /** Creates a mapping with a scoped implementation and a threat model entry (GC-H006). */
    public static RiskControlMapping forScopedThreat(
            Project project,
            ScopedControlImplementation scopedImplementation,
            ThreatModel threatModel,
            MappingControlRole controlRole) {
        RiskControlMapping m = new RiskControlMapping();
        m.project = project;
        m.scopedImplementation = scopedImplementation;
        m.threatModel = threatModel;
        m.controlRole = controlRole;
        return m;
    }

    // ---- Control-side endpoint type (computed) ----

    public boolean isControlSide() {
        return control != null;
    }

    public boolean isScopedImplementationSide() {
        return scopedImplementation != null;
    }

    // ---- Analysis-side endpoint type (computed) ----

    public boolean isThreatSide() {
        return threatModel != null;
    }

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

    public ThreatModel getThreatModel() {
        return threatModel;
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
