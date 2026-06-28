package com.keplerops.groundcontrol.domain.derivation.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "boundary_model_gap")
public class BoundaryModelGap extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private BoundaryModelSnapshot snapshot;

    @Column(name = "source_fact_key", nullable = false, length = 200)
    private String sourceFactKey;

    @Column(name = "source_fact_kind", nullable = false, length = 40)
    private String sourceFactKind;

    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Column(nullable = false, length = 40)
    private String reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String detail;

    protected BoundaryModelGap() {
        // JPA
    }

    public BoundaryModelGap(
            Project project,
            BoundaryModelSnapshot snapshot,
            String sourceFactKey,
            String sourceFactKind,
            String sourcePath,
            String reason,
            String detail) {
        this.project = project;
        this.snapshot = snapshot;
        this.sourceFactKey = sourceFactKey;
        this.sourceFactKind = sourceFactKind;
        this.sourcePath = sourcePath;
        this.reason = reason;
        this.detail = detail;
    }

    public Project getProject() {
        return project;
    }

    public BoundaryModelSnapshot getSnapshot() {
        return snapshot;
    }

    public String getSourceFactKey() {
        return sourceFactKey;
    }

    public String getSourceFactKind() {
        return sourceFactKind;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }
}
