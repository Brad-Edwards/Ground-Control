package com.keplerops.groundcontrol.domain.architecturemodel.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(
        name = "architecture_model_snapshot",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "model_version"}))
public class ArchitectureModelSnapshot extends BaseEntity {

    public static final String SCHEMA_VERSION = "architecture-model/v1";

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "derivation_run_id")
    private DerivationRun derivationRun;

    @Column(name = "schema_version", nullable = false, length = 40)
    private String schemaVersion = SCHEMA_VERSION;

    @Column(name = "model_version", nullable = false, length = 120)
    private String modelVersion;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "element_count", nullable = false)
    private int elementCount;

    @Column(name = "flow_count", nullable = false)
    private int flowCount;

    protected ArchitectureModelSnapshot() {
        // JPA
    }

    public ArchitectureModelSnapshot(
            Project project,
            DerivationRun derivationRun,
            String modelVersion,
            String commitSha,
            String source,
            String createdBy) {
        this.project = project;
        this.derivationRun = derivationRun;
        this.modelVersion = modelVersion;
        this.commitSha = commitSha;
        this.source = source;
        this.createdBy = createdBy;
    }

    public Project getProject() {
        return project;
    }

    public DerivationRun getDerivationRun() {
        return derivationRun;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getSource() {
        return source;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public int getElementCount() {
        return elementCount;
    }

    public int getFlowCount() {
        return flowCount;
    }

    public void setCounts(int elementCount, int flowCount) {
        this.elementCount = elementCount;
        this.flowCount = flowCount;
    }
}
