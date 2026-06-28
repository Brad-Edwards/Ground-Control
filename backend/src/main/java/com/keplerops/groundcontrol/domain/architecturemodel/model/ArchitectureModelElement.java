package com.keplerops.groundcontrol.domain.architecturemodel.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
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

@Entity
@Audited
@Table(
        name = "architecture_model_element",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "stable_key"}))
public class ArchitectureModelElement extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "stable_key", nullable = false, length = 200)
    private String stableKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "element_kind", nullable = false, length = 40)
    private ArchitectureModelElementKind elementKind;

    protected ArchitectureModelElement() {
        // JPA
    }

    public ArchitectureModelElement(Project project, String stableKey, ArchitectureModelElementKind elementKind) {
        this.project = project;
        this.stableKey = stableKey;
        this.elementKind = elementKind;
    }

    public Project getProject() {
        return project;
    }

    public String getStableKey() {
        return stableKey;
    }

    public ArchitectureModelElementKind getElementKind() {
        return elementKind;
    }
}
