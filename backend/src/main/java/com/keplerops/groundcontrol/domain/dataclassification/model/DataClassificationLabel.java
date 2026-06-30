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
 * A single sensitivity label in a project's data classification lattice (GC-GRC-006). {@code rank}
 * is a display/ordering hint only — the authoritative dominance is the explicit permitted-flow
 * relation held by {@link DataClassificationFlowRule}, never inferred from rank order.
 */
@Entity
@Audited
@Table(
        name = "data_classification_label",
        uniqueConstraints = @UniqueConstraint(columnNames = {"lattice_id", "label_key"}))
public class DataClassificationLabel extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lattice_id", nullable = false)
    private DataClassificationLattice lattice;

    @Column(name = "label_key", nullable = false, length = 120)
    private String labelKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column
    private Integer rank;

    protected DataClassificationLabel() {
        // JPA
    }

    public DataClassificationLabel(
            Project project,
            DataClassificationLattice lattice,
            String labelKey,
            String displayName,
            String description,
            Integer rank) {
        this.project = project;
        this.lattice = lattice;
        this.labelKey = labelKey;
        this.displayName = displayName;
        this.description = description;
        this.rank = rank;
    }

    public Project getProject() {
        return project;
    }

    public DataClassificationLattice getLattice() {
        return lattice;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public Integer getRank() {
        return rank;
    }
}
