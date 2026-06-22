package com.keplerops.groundcontrol.domain.riskappetite.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
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

/**
 * Organizational risk appetite and tolerance policy (GC-T005). An appetite profile pairs a
 * qualitative appetite statement with a set of methodology-appropriate {@link ToleranceThreshold}
 * ceilings, expressed in the semantics of a single {@link MethodologyFamily} (FAIR monetary /
 * frequency / probability, NIST or ISO ordinal bands).
 *
 * <p>Profiles are <em>versioned</em>: identity is the stable {@code appetiteKey}, and each
 * {@code version} is a distinct row unique within its project. Each version carries an explicit
 * business effective window ({@code effectiveFrom} / {@code effectiveTo}) so "the appetite in force
 * as of date X" is a first-class query rather than an Envers history reconstruction. The service
 * enforces non-overlapping effective windows among {@code ACTIVE} versions of the same key. Envers
 * (@Audited) records field-level change history on top of the explicit versioning.
 */
@Entity
@Audited
@Table(
        name = "risk_appetite_profile",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "appetite_key", "version"}))
public class RiskAppetiteProfile extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "appetite_key", nullable = false, length = 100)
    private String appetiteKey;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "methodology_family", nullable = false, length = 30)
    private MethodologyFamily methodologyFamily;

    @Column(name = "appetite_statement", columnDefinition = "TEXT")
    private String appetiteStatement;

    @Convert(converter = JacksonTextCollectionConverters.ToleranceThresholdListConverter.class)
    @Column(name = "tolerance_thresholds", columnDefinition = "TEXT")
    private List<ToleranceThreshold> toleranceThresholds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskAppetiteProfileStatus status = RiskAppetiteProfileStatus.DRAFT;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    protected RiskAppetiteProfile() {
        // JPA
    }

    public RiskAppetiteProfile(
            Project project,
            String appetiteKey,
            String name,
            String version,
            MethodologyFamily methodologyFamily,
            Instant effectiveFrom) {
        this.project = project;
        this.appetiteKey = appetiteKey;
        this.name = name;
        this.version = version;
        this.methodologyFamily = methodologyFamily;
        this.effectiveFrom = effectiveFrom;
    }

    public Project getProject() {
        return project;
    }

    public String getAppetiteKey() {
        return appetiteKey;
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

    public MethodologyFamily getMethodologyFamily() {
        return methodologyFamily;
    }

    public void setMethodologyFamily(MethodologyFamily methodologyFamily) {
        this.methodologyFamily = methodologyFamily;
    }

    public String getAppetiteStatement() {
        return appetiteStatement;
    }

    public void setAppetiteStatement(String appetiteStatement) {
        this.appetiteStatement = appetiteStatement;
    }

    public List<ToleranceThreshold> getToleranceThresholds() {
        return toleranceThresholds;
    }

    public void setToleranceThresholds(List<ToleranceThreshold> toleranceThresholds) {
        this.toleranceThresholds = toleranceThresholds;
    }

    public RiskAppetiteProfileStatus getStatus() {
        return status;
    }

    public void setStatus(RiskAppetiteProfileStatus status) {
        this.status = status;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }
}
