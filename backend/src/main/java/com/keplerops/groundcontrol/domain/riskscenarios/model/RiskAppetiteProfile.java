package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.List;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-T005: Versioned, project-scoped Risk Appetite Profile.
 *
 * <p>An appetite profile carries a list of {@link RiskAppetiteTolerance} bands
 * (typed thresholds per risk category) and is the input to
 * {@code RiskAppetiteEvaluator} — the shared kernel that decides whether a
 * {@code RiskAssessmentResult} or {@code RiskRegisterRecord} sits within or
 * exceeds tolerance.
 *
 * <p>Uniqueness is {@code (project_id, profile_key, version)} so a project can
 * carry multiple appetite profiles (e.g. by board cycle) and version them
 * monotonically. Active vs. archived is signalled by {@code active}; only one
 * profile per {@code profileKey} should be active at a time (enforced by
 * service-layer guard).
 */
@Entity
@Audited
@Table(
        name = "risk_appetite_profile",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "profile_key", "version"}))
public class RiskAppetiteProfile extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "profile_key", nullable = false, length = 100)
    private String profileKey;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(name = "appetite_statement", columnDefinition = "TEXT")
    private String appetiteStatement;

    @Column(name = "owner", length = 200)
    private String owner;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Convert(converter = JacksonTextCollectionConverters.RiskAppetiteToleranceListConverter.class)
    @Column(name = "tolerances", columnDefinition = "TEXT")
    private List<RiskAppetiteTolerance> tolerances;

    protected RiskAppetiteProfile() {
        // JPA
    }

    public RiskAppetiteProfile(Project project, String profileKey, String name, String version) {
        this.project = project;
        this.profileKey = profileKey;
        this.name = name;
        this.version = version;
    }

    public Project getProject() {
        return project;
    }

    public String getProfileKey() {
        return profileKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAppetiteStatement() {
        return appetiteStatement;
    }

    public void setAppetiteStatement(String appetiteStatement) {
        this.appetiteStatement = appetiteStatement;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public List<RiskAppetiteTolerance> getTolerances() {
        return tolerances;
    }

    public void setTolerances(List<RiskAppetiteTolerance> tolerances) {
        this.tolerances = tolerances;
    }
}
