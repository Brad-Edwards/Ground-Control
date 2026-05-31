package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CampaignPhase;
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

/**
 * GC-T006: Risk Assessment Campaign aggregate.
 *
 * <p>Each campaign owns a phase state machine ({@link CampaignPhase}) plus an
 * optional binding to a methodology profile, an appetite profile (used for
 * the EVALUATION phase via {@code RiskAppetiteEvaluator}), and free-form
 * structured fields for scope and approvals. The methodology binding becomes
 * immutable once the campaign reaches {@code EVALUATION} (or beyond) — see
 * {@link CampaignPhase#methodologyLocked()}.
 *
 * <p>Linkage to risk scenarios, assessment results, and treatment plans is
 * implicit via FKs on the consumed rows; this aggregate carries only campaign
 * metadata and the structured {@code scope} / {@code approvalMetadata} maps.
 */
@Entity
@Audited
@Table(name = "risk_assessment_campaign", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class RiskAssessmentCampaign extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String uid;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "owner", length = 200)
    private String owner;

    @Column(name = "objective", columnDefinition = "TEXT")
    private String objective;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 30)
    private CampaignPhase phase = CampaignPhase.PLANNING;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "methodology_profile_id")
    private MethodologyProfile methodologyProfile;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "appetite_profile_id")
    private RiskAppetiteProfile appetiteProfile;

    @Column(name = "scheduled_start")
    private Instant scheduledStart;

    @Column(name = "scheduled_end")
    private Instant scheduledEnd;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "scope", columnDefinition = "TEXT")
    private Map<String, Object> scope;

    @Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)
    @Column(name = "approval_metadata", columnDefinition = "TEXT")
    private Map<String, Object> approvalMetadata;

    @Convert(converter = JacksonTextCollectionConverters.StringListConverter.class)
    @Column(name = "scoped_asset_ids", columnDefinition = "TEXT")
    private List<String> scopedAssetIds;

    protected RiskAssessmentCampaign() {
        // JPA
    }

    public RiskAssessmentCampaign(Project project, String uid, String title) {
        this.project = project;
        this.uid = uid;
        this.title = title;
    }

    /**
     * Advance the campaign to {@code target}. The transition is validated through
     * {@link CampaignPhase#canTransitionTo(CampaignPhase)}; reaching {@code EVALUATION}
     * (or later) without a bound methodology profile is rejected.
     */
    public void advanceTo(CampaignPhase target) {
        if (target == null || !phase.canTransitionTo(target)) {
            throw new DomainValidationException("Cannot transition campaign from " + phase + " to " + target);
        }
        if (target.methodologyLocked() && methodologyProfile == null) {
            throw new DomainValidationException(
                    "Campaign cannot advance to " + target + " without a bound methodology profile");
        }
        this.phase = target;
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

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getObjective() {
        return objective;
    }

    public void setObjective(String objective) {
        this.objective = objective;
    }

    public CampaignPhase getPhase() {
        return phase;
    }

    public MethodologyProfile getMethodologyProfile() {
        return methodologyProfile;
    }

    /**
     * Set / change the bound methodology profile. Rejected once the campaign
     * has reached EVALUATION or beyond — the methodology is immutable for the
     * remainder of the campaign's life, including its CLOSED tail (re-running
     * with a different methodology is a new campaign).
     */
    public void setMethodologyProfile(MethodologyProfile methodologyProfile) {
        if (methodologyImmutable()
                && this.methodologyProfile != null
                && (methodologyProfile == null
                        || !methodologyProfile.getId().equals(this.methodologyProfile.getId()))) {
            throw new DomainValidationException("Methodology profile is locked for a campaign in phase " + phase);
        }
        this.methodologyProfile = methodologyProfile;
    }

    /**
     * True once the campaign has reached EVALUATION (or any later phase
     * including CLOSED). At that point the bound methodology profile is
     * frozen for audit-trail integrity even when the campaign is later
     * closed.
     */
    private boolean methodologyImmutable() {
        return phase == CampaignPhase.EVALUATION || phase == CampaignPhase.TREATMENT || phase == CampaignPhase.CLOSED;
    }

    public RiskAppetiteProfile getAppetiteProfile() {
        return appetiteProfile;
    }

    public void setAppetiteProfile(RiskAppetiteProfile appetiteProfile) {
        this.appetiteProfile = appetiteProfile;
    }

    public Instant getScheduledStart() {
        return scheduledStart;
    }

    public void setScheduledStart(Instant scheduledStart) {
        this.scheduledStart = scheduledStart;
    }

    public Instant getScheduledEnd() {
        return scheduledEnd;
    }

    public void setScheduledEnd(Instant scheduledEnd) {
        this.scheduledEnd = scheduledEnd;
    }

    public Map<String, Object> getScope() {
        return scope;
    }

    public void setScope(Map<String, Object> scope) {
        this.scope = scope;
    }

    public Map<String, Object> getApprovalMetadata() {
        return approvalMetadata;
    }

    public void setApprovalMetadata(Map<String, Object> approvalMetadata) {
        this.approvalMetadata = approvalMetadata;
    }

    public List<String> getScopedAssetIds() {
        return scopedAssetIds;
    }

    public void setScopedAssetIds(List<String> scopedAssetIds) {
        this.scopedAssetIds = scopedAssetIds;
    }
}
