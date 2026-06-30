package com.keplerops.groundcontrol.domain.evidence.campaign.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignRunStatus;
import com.keplerops.groundcontrol.domain.projects.model.Project;
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
import java.util.UUID;

/**
 * One execution of an {@link EvidenceCampaign} over a discrete time window
 * (GC-S005). Runs are immutable operational telemetry — not Envers-audited —
 * and are aged out by retention according to the parent campaign's
 * {@code retentionDays}.
 *
 * <p>The {@code (campaign_id, window_start)} uniqueness constraint guarantees a
 * given scheduling window is recorded at most once, so a re-entrant or
 * duplicated sweep cannot persist two runs for the same window.
 */
@Entity
@Table(
        name = "evidence_campaign_run",
        uniqueConstraints = @UniqueConstraint(columnNames = {"campaign_id", "window_start"}))
public class EvidenceCampaignRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private EvidenceCampaign campaign;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceCampaignRunStatus status;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "artifact_count", nullable = false)
    private int artifactCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    /** Bounded, secret-stripped error summary. Never carries a raw secret. */
    @Column(name = "sanitized_error", columnDefinition = "TEXT")
    private String sanitizedError;

    @Column(name = "produced_artifact_ids", columnDefinition = "TEXT")
    @Convert(converter = UuidListConverter.class)
    private List<UUID> producedArtifactIds;

    protected EvidenceCampaignRun() {
        // JPA
    }

    public EvidenceCampaignRun(
            EvidenceCampaign campaign,
            Project project,
            EvidenceCampaignRunStatus status,
            Instant windowStart,
            Instant windowEnd) {
        this.campaign = campaign;
        this.project = project;
        this.status = status;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public EvidenceCampaign getCampaign() {
        return campaign;
    }

    public Project getProject() {
        return project;
    }

    public EvidenceCampaignRunStatus getStatus() {
        return status;
    }

    public void setStatus(EvidenceCampaignRunStatus status) {
        this.status = status;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getArtifactCount() {
        return artifactCount;
    }

    public void setArtifactCount(int artifactCount) {
        this.artifactCount = artifactCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public String getSanitizedError() {
        return sanitizedError;
    }

    public void setSanitizedError(String sanitizedError) {
        this.sanitizedError = sanitizedError;
    }

    public List<UUID> getProducedArtifactIds() {
        return producedArtifactIds;
    }

    public void setProducedArtifactIds(List<UUID> producedArtifactIds) {
        this.producedArtifactIds = producedArtifactIds;
    }
}
