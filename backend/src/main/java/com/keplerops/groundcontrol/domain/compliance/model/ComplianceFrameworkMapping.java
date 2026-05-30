package com.keplerops.groundcontrol.domain.compliance.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Canonical compliance-framework mapping aggregate for GC-I002 / GC-I005 /
 * GC-I007. A single aggregate with a polymorphic source endpoint
 * (Requirement OR Control via paired nullable FKs + DB XOR check) so the same
 * persistence shape covers requirement-to-framework (GC-I002) and
 * control-to-framework (GC-I005) mappings — the analytical join in
 * CrossFrameworkGapService can then walk one table.
 *
 * <p>The framework side is represented by a typed enum identifier (SOC2, SOX,
 * ISO_27001, NIST_CSF, PCI_DSS) plus an optional free-form
 * {@code frameworkIdentifier} string for genuine externals that do not yet
 * have a first-class enum constant. {@code frameworkVersion} captures the
 * concrete version of the framework spec the mapping was authored against
 * (e.g. "2017 TSC" for SOC2, "2022" for ISO 27001).
 *
 * <p>Per-mapping {@code coverageLevel} captures whether the endpoint fully
 * satisfies the element, partially satisfies it, or stands as a compensating
 * control per GC-I005.
 *
 * <p>Per mcp-grc-entity-crud-preflight: this aggregate is the promoted form of
 * the historical AuditLinkTargetType.FRAMEWORK link type. The audit-side enum
 * value is preserved for backward compatibility until the next ADR carves it
 * out; new mappings should be authored against this aggregate.
 */
@Entity
@Audited
@Table(
        name = "compliance_framework_mapping",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_cfm_requirement_framework_element",
                    columnNames = {"requirement_id", "framework", "framework_element"}),
            @UniqueConstraint(
                    name = "uq_cfm_control_framework_element",
                    columnNames = {"control_id", "framework", "framework_element"})
        })
public class ComplianceFrameworkMapping extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // ---- Polymorphic source endpoint (exactly one of requirement/control) ----

    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id")
    private Requirement requirement;

    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "control_id")
    private Control control;

    // ---- Framework side ----

    @Enumerated(EnumType.STRING)
    @Column(name = "framework", nullable = false, length = 40)
    private ComplianceFrameworkIdentifier framework;

    @Column(name = "framework_identifier", length = 200)
    private String frameworkIdentifier;

    @Column(name = "framework_version", length = 60)
    private String frameworkVersion;

    @Column(name = "framework_element", nullable = false, length = 200)
    private String frameworkElement;

    // ---- Per-mapping qualification ----

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_level", nullable = false, length = 20)
    private CoverageLevel coverageLevel;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    protected ComplianceFrameworkMapping() {
        // JPA
    }

    /** Creates a mapping with a requirement source endpoint (GC-I002 path). */
    public static ComplianceFrameworkMapping forRequirement(
            Project project,
            Requirement requirement,
            ComplianceFrameworkIdentifier framework,
            String frameworkElement,
            CoverageLevel coverageLevel) {
        ComplianceFrameworkMapping m = new ComplianceFrameworkMapping();
        m.project = project;
        m.requirement = requirement;
        m.framework = framework;
        m.frameworkElement = frameworkElement;
        m.coverageLevel = coverageLevel;
        return m;
    }

    /** Creates a mapping with a control source endpoint (GC-I005 path). */
    public static ComplianceFrameworkMapping forControl(
            Project project,
            Control control,
            ComplianceFrameworkIdentifier framework,
            String frameworkElement,
            CoverageLevel coverageLevel) {
        ComplianceFrameworkMapping m = new ComplianceFrameworkMapping();
        m.project = project;
        m.control = control;
        m.framework = framework;
        m.frameworkElement = frameworkElement;
        m.coverageLevel = coverageLevel;
        return m;
    }

    public boolean isRequirementSide() {
        return requirement != null;
    }

    public boolean isControlSide() {
        return control != null;
    }

    // ---- Getters / setters ----

    public Project getProject() {
        return project;
    }

    public Requirement getRequirement() {
        return requirement;
    }

    public Control getControl() {
        return control;
    }

    public ComplianceFrameworkIdentifier getFramework() {
        return framework;
    }

    public void setFramework(ComplianceFrameworkIdentifier framework) {
        this.framework = framework;
    }

    public String getFrameworkIdentifier() {
        return frameworkIdentifier;
    }

    public void setFrameworkIdentifier(String frameworkIdentifier) {
        this.frameworkIdentifier = frameworkIdentifier;
    }

    public String getFrameworkVersion() {
        return frameworkVersion;
    }

    public void setFrameworkVersion(String frameworkVersion) {
        this.frameworkVersion = frameworkVersion;
    }

    public String getFrameworkElement() {
        return frameworkElement;
    }

    public void setFrameworkElement(String frameworkElement) {
        this.frameworkElement = frameworkElement;
    }

    public CoverageLevel getCoverageLevel() {
        return coverageLevel;
    }

    public void setCoverageLevel(CoverageLevel coverageLevel) {
        this.coverageLevel = coverageLevel;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }
}
