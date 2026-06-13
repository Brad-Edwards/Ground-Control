package com.keplerops.groundcontrol.domain.derivation.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationCaptureLimitDraft;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "derivation_capture_limit")
public class DerivationCaptureLimit extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "derivation_run_id", nullable = false)
    private DerivationRun derivationRun;

    @Column(name = "adapter_id", length = 100)
    private String adapterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CaptureLimitReason reason;

    @Column(nullable = false, length = 80)
    private String language;

    @Column(nullable = false, length = 80)
    private String surface;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected DerivationCaptureLimit() {
        // JPA
    }

    public DerivationCaptureLimit(
            Project project, DerivationRun derivationRun, DerivationCaptureLimitDraft captureLimit) {
        this.project = project;
        this.derivationRun = derivationRun;
        this.adapterId = captureLimit.adapterId();
        this.reason = captureLimit.reason();
        this.language = captureLimit.language();
        this.surface = captureLimit.surface();
        this.detail = captureLimit.detail();
        this.commitSha = captureLimit.commitSha();
        this.capturedAt = captureLimit.capturedAt();
    }

    public Project getProject() {
        return project;
    }

    public DerivationRun getDerivationRun() {
        return derivationRun;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public CaptureLimitReason getReason() {
        return reason;
    }

    public String getLanguage() {
        return language;
    }

    public String getSurface() {
        return surface;
    }

    public String getDetail() {
        return detail;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
