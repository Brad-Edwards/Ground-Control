package com.keplerops.groundcontrol.domain.dataclassification.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * Root of a project-scoped data classification lattice (GC-GRC-006). One row per project marks that
 * a {@link DataClassificationSource#CUSTOM} taxonomy + permitted-flow policy is stored server-side;
 * its absence means the shipped default lattice is in effect. {@code policyVersion} is a content
 * digest recomputed on every write so evaluations can record which policy they ran against.
 */
@Entity
@Audited
@Table(name = "data_classification_lattice", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id"}))
public class DataClassificationLattice extends BaseEntity {

    public static final String SCHEMA_VERSION = "data-classification-lattice/v1";

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "schema_version", nullable = false, length = 60)
    private String schemaVersion = SCHEMA_VERSION;

    @Column(name = "policy_version", nullable = false, length = 80)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataClassificationSource source;

    @Column(name = "label_count", nullable = false)
    private int labelCount;

    @Column(name = "edge_count", nullable = false)
    private int edgeCount;

    protected DataClassificationLattice() {
        // JPA
    }

    public DataClassificationLattice(
            Project project, String policyVersion, DataClassificationSource source, int labelCount, int edgeCount) {
        this.project = project;
        this.policyVersion = policyVersion;
        this.source = source;
        this.labelCount = labelCount;
        this.edgeCount = edgeCount;
    }

    public Project getProject() {
        return project;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public DataClassificationSource getSource() {
        return source;
    }

    public int getLabelCount() {
        return labelCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }
}
