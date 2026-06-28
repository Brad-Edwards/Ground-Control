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
@Table(name = "boundary_model_snapshot")
public class BoundaryModelSnapshot extends BaseEntity {

    public static final String SCHEMA_VERSION = "boundary-model/v1";

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "derivation_run_id", nullable = false)
    private DerivationRun derivationRun;

    @Column(name = "schema_version", nullable = false, length = 40)
    private String schemaVersion = SCHEMA_VERSION;

    @Column(name = "boundary_set_version", nullable = false, length = 80)
    private String boundarySetVersion;

    @Column(name = "architecture_model_version", nullable = false, length = 120)
    private String architectureModelVersion;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "declaration_digest", nullable = false, length = 80)
    private String declarationDigest;

    @Column(name = "boundary_count", nullable = false)
    private int boundaryCount;

    @Column(name = "assignment_count", nullable = false)
    private int assignmentCount;

    @Column(name = "gap_count", nullable = false)
    private int gapCount;

    protected BoundaryModelSnapshot() {
        // JPA
    }

    public BoundaryModelSnapshot(
            Project project,
            DerivationRun derivationRun,
            String boundarySetVersion,
            String architectureModelVersion,
            String declarationDigest) {
        this.project = project;
        this.derivationRun = derivationRun;
        this.boundarySetVersion = boundarySetVersion;
        this.architectureModelVersion = architectureModelVersion;
        this.commitSha = derivationRun.getCommitSha();
        this.declarationDigest = declarationDigest;
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

    public String getBoundarySetVersion() {
        return boundarySetVersion;
    }

    public String getArchitectureModelVersion() {
        return architectureModelVersion;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getDeclarationDigest() {
        return declarationDigest;
    }

    public int getBoundaryCount() {
        return boundaryCount;
    }

    public int getAssignmentCount() {
        return assignmentCount;
    }

    public int getGapCount() {
        return gapCount;
    }

    public void setCounts(int boundaryCount, int assignmentCount, int gapCount) {
        this.boundaryCount = boundaryCount;
        this.assignmentCount = assignmentCount;
        this.gapCount = gapCount;
    }
}
