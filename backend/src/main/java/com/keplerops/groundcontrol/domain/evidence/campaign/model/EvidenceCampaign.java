package com.keplerops.groundcontrol.domain.evidence.campaign.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignStatus;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.StringObjectMapConverter;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.UuidListConverter;
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
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Scheduled evidence-collection campaign per GC-S005.
 *
 * <p>A campaign is a durable, project-scoped recurring directive: at the
 * configured {@link EvidenceCampaignFrequency} it invokes a named evidence
 * collection adapter over a configured scope and persists the collected results
 * as {@code EvidenceArtifact}s, optionally linking them to a set of target
 * controls. The campaign carries only a {@code credentialRef} — an indirection
 * key resolved at collection time — and never the raw secret.
 *
 * <p>The entity is project-scoped through {@link Project} (audited as
 * {@code @NotAudited} per ADR-038). {@code nextRunAt} is the scheduling cursor;
 * the scheduled sweep claims a due campaign by conditionally advancing this
 * cursor (see {@code markClaimedIfDue}) so two concurrent sweep ticks cannot
 * double-run the same window.
 */
@Entity
@Audited
@Table(name = "evidence_campaign", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class EvidenceCampaign extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String uid;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceCampaignFrequency frequency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceCampaignStatus status;

    @Column(name = "adapter_name", nullable = false, length = 100)
    private String adapterName;

    @Column(name = "scope_type", nullable = false, length = 120)
    private String scopeType;

    @Column(name = "schema_id", length = 120)
    private String schemaId;

    @Column(name = "connection_profile_id", nullable = false, length = 200)
    private String connectionProfileId;

    @Column(name = "connection_endpoint", nullable = false, length = 500)
    private String connectionEndpoint;

    /** Indirection key for the collection credential. Never the raw secret. */
    @Column(name = "credential_ref", nullable = false, length = 200)
    private String credentialRef;

    @Column(name = "scope_criteria", columnDefinition = "TEXT")
    @Convert(converter = StringObjectMapConverter.class)
    private Map<String, Object> scopeCriteria;

    @Column(name = "target_control_ids", columnDefinition = "TEXT")
    @Convert(converter = UuidListConverter.class)
    private List<UUID> targetControlIds;

    @Column(name = "retention_days")
    private Integer retentionDays;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    protected EvidenceCampaign() {
        // JPA
    }

    public EvidenceCampaign(
            Project project,
            String uid,
            String name,
            EvidenceCampaignFrequency frequency,
            String adapterName,
            String scopeType,
            String connectionProfileId,
            String connectionEndpoint,
            String credentialRef,
            Instant nextRunAt) {
        this.project = project;
        this.uid = uid;
        this.name = name;
        this.frequency = frequency;
        this.status = EvidenceCampaignStatus.ACTIVE;
        this.adapterName = adapterName;
        this.scopeType = scopeType;
        this.connectionProfileId = connectionProfileId;
        this.connectionEndpoint = connectionEndpoint;
        this.credentialRef = credentialRef;
        this.nextRunAt = nextRunAt;
    }

    public Project getProject() {
        return project;
    }

    public String getUid() {
        return uid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public EvidenceCampaignFrequency getFrequency() {
        return frequency;
    }

    public void setFrequency(EvidenceCampaignFrequency frequency) {
        this.frequency = frequency;
    }

    public EvidenceCampaignStatus getStatus() {
        return status;
    }

    public void setStatus(EvidenceCampaignStatus status) {
        this.status = status;
    }

    public String getAdapterName() {
        return adapterName;
    }

    public void setAdapterName(String adapterName) {
        this.adapterName = adapterName;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public String getSchemaId() {
        return schemaId;
    }

    public void setSchemaId(String schemaId) {
        this.schemaId = schemaId;
    }

    public String getConnectionProfileId() {
        return connectionProfileId;
    }

    public void setConnectionProfileId(String connectionProfileId) {
        this.connectionProfileId = connectionProfileId;
    }

    public String getConnectionEndpoint() {
        return connectionEndpoint;
    }

    public void setConnectionEndpoint(String connectionEndpoint) {
        this.connectionEndpoint = connectionEndpoint;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public Map<String, Object> getScopeCriteria() {
        return scopeCriteria;
    }

    public void setScopeCriteria(Map<String, Object> scopeCriteria) {
        this.scopeCriteria = scopeCriteria;
    }

    public List<UUID> getTargetControlIds() {
        return targetControlIds;
    }

    public void setTargetControlIds(List<UUID> targetControlIds) {
        this.targetControlIds = targetControlIds;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }
}
