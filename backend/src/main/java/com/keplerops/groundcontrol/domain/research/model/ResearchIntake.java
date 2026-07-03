package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
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
import java.util.ArrayList;
import java.util.List;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Research-specific intake metadata captured at project create-or-update time.
 * One row per {@link Project} when {@code project.type = RESEARCH}; absent for
 * other types. See ADR-056.
 *
 * <p>The 1:1 invariant is enforced by the UNIQUE constraint on
 * {@code project_id} (declared on the {@code @ManyToOne} {@code @JoinColumn}
 * and reinforced by the DB-level UNIQUE on the migration). The "intake
 * required iff type=RESEARCH" invariant is enforced by Bean Validation at the
 * API boundary and a service-layer guard for bypass writes.
 */
@Entity
@Audited
@Table(name = "research_intake", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id"}))
public class ResearchIntake extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String goal;

    @Column(name = "paper_context", columnDefinition = "TEXT")
    private String paperContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "contribution_type", nullable = false, length = 40)
    private ContributionType contributionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "intended_output", nullable = false, length = 40)
    private IntendedOutput intendedOutput;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_level", nullable = false, length = 20)
    private AutonomyLevel autonomyLevel;

    @Convert(converter = JacksonTextCollectionConverters.StringListConverter.class)
    @Column(name = "allowed_tools", nullable = false, columnDefinition = "TEXT")
    private List<String> allowedTools = new ArrayList<>();

    @Column(name = "privacy_constraints", columnDefinition = "TEXT")
    private String privacyConstraints;

    // Structured, default-deny data-egress policy (GC-RSCH-N006 / ADR-086 §2).
    // The run snapshots this at start; absence of an allow rule is deny (local
    // only). Distinct from the free-text privacyConstraints, which is operator
    // context only, never the enforcement input.
    @Convert(converter = JacksonTextCollectionConverters.ResearchEgressAllowanceListConverter.class)
    @Column(name = "egress_policy", nullable = false, columnDefinition = "TEXT")
    private List<ResearchEgressAllowance> egressPolicy = new ArrayList<>();

    @Column(name = "budget_tokens")
    private Long budgetTokens;

    @Column(name = "budget_wall_clock_minutes")
    private Integer budgetWallClockMinutes;

    @Column(name = "budget_cost_usd_micros")
    private Long budgetCostUsdMicros;

    protected ResearchIntake() {
        // JPA
    }

    public ResearchIntake(
            Project project,
            String goal,
            ContributionType contributionType,
            IntendedOutput intendedOutput,
            AutonomyLevel autonomyLevel,
            List<String> allowedTools) {
        this.project = project;
        this.goal = goal;
        this.contributionType = contributionType;
        this.intendedOutput = intendedOutput;
        this.autonomyLevel = autonomyLevel;
        this.allowedTools = allowedTools == null ? new ArrayList<>() : new ArrayList<>(allowedTools);
    }

    public Project getProject() {
        return project;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getPaperContext() {
        return paperContext;
    }

    public void setPaperContext(String paperContext) {
        this.paperContext = paperContext;
    }

    public ContributionType getContributionType() {
        return contributionType;
    }

    public void setContributionType(ContributionType contributionType) {
        this.contributionType = contributionType;
    }

    public IntendedOutput getIntendedOutput() {
        return intendedOutput;
    }

    public void setIntendedOutput(IntendedOutput intendedOutput) {
        this.intendedOutput = intendedOutput;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public void setAutonomyLevel(AutonomyLevel autonomyLevel) {
        this.autonomyLevel = autonomyLevel;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools == null ? new ArrayList<>() : new ArrayList<>(allowedTools);
    }

    public String getPrivacyConstraints() {
        return privacyConstraints;
    }

    public void setPrivacyConstraints(String privacyConstraints) {
        this.privacyConstraints = privacyConstraints;
    }

    public List<ResearchEgressAllowance> getEgressPolicy() {
        return egressPolicy;
    }

    public void setEgressPolicy(List<ResearchEgressAllowance> egressPolicy) {
        this.egressPolicy = egressPolicy == null ? new ArrayList<>() : new ArrayList<>(egressPolicy);
    }

    public Long getBudgetTokens() {
        return budgetTokens;
    }

    public void setBudgetTokens(Long budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    public Integer getBudgetWallClockMinutes() {
        return budgetWallClockMinutes;
    }

    public void setBudgetWallClockMinutes(Integer budgetWallClockMinutes) {
        this.budgetWallClockMinutes = budgetWallClockMinutes;
    }

    public Long getBudgetCostUsdMicros() {
        return budgetCostUsdMicros;
    }

    public void setBudgetCostUsdMicros(Long budgetCostUsdMicros) {
        this.budgetCostUsdMicros = budgetCostUsdMicros;
    }
}
