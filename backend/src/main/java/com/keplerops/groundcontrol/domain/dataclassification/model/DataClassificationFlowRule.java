package com.keplerops.groundcontrol.domain.dataclassification.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
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

/**
 * A permitted-flow edge in a project's data classification lattice (GC-GRC-006). The presence of a
 * {@code (from_label_key, to_label_key)} row means data labeled {@code from} may flow to a sink
 * labeled {@code to}; absence means the flow is denied. Rows store the reflexive-transitive closure
 * of the authored relation so the allow decision is total and deterministic for every pair.
 */
@Entity
@Audited
@Table(
        name = "data_classification_flow_rule",
        uniqueConstraints = @UniqueConstraint(columnNames = {"lattice_id", "from_label_key", "to_label_key"}))
public class DataClassificationFlowRule extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lattice_id", nullable = false)
    private DataClassificationLattice lattice;

    @Column(name = "from_label_key", nullable = false, length = 120)
    private String fromLabelKey;

    @Column(name = "to_label_key", nullable = false, length = 120)
    private String toLabelKey;

    protected DataClassificationFlowRule() {
        // JPA
    }

    public DataClassificationFlowRule(
            Project project, DataClassificationLattice lattice, String fromLabelKey, String toLabelKey) {
        this.project = project;
        this.lattice = lattice;
        this.fromLabelKey = fromLabelKey;
        this.toLabelKey = toLabelKey;
    }

    public Project getProject() {
        return project;
    }

    public DataClassificationLattice getLattice() {
        return lattice;
    }

    public String getFromLabelKey() {
        return fromLabelKey;
    }

    public String getToLabelKey() {
        return toLabelKey;
    }
}
