package com.keplerops.groundcontrol.domain.riskcontrol.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.controls.model.Control;
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
 * A named, project-scoped deployment or scoping of a catalog {@link Control}
 * for a specific operational context, optionally anchored to an
 * {@link OperationalAsset} (GC-T003 C1).
 *
 * <p>This aggregate is the first-class "scoped control implementation" endpoint
 * that GC-T003 requires. It allows the same catalog control to appear in multiple
 * mappings with different scope descriptions, asset anchors, or implementation
 * details without mutating the catalog entry.
 */
@Entity
@Audited
@Table(
        name = "scoped_control_implementation",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class ScopedControlImplementation extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String uid;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "control_id", nullable = false)
    private Control control;

    @Column(nullable = false, length = 200)
    private String name;

    /** Structured description of how the catalog control is scoped/deployed in this context. */
    @Column(name = "implementation_scope", columnDefinition = "TEXT")
    private String implementationScope;

    /** Optional operational asset that bounds this scoped implementation. */
    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operational_asset_id")
    private OperationalAsset operationalAsset;

    protected ScopedControlImplementation() {
        // JPA
    }

    public ScopedControlImplementation(Project project, String uid, Control control, String name) {
        this.project = project;
        this.uid = uid;
        this.control = control;
        this.name = name;
    }

    public Project getProject() {
        return project;
    }

    public String getUid() {
        return uid;
    }

    public Control getControl() {
        return control;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImplementationScope() {
        return implementationScope;
    }

    public void setImplementationScope(String implementationScope) {
        this.implementationScope = implementationScope;
    }

    public OperationalAsset getOperationalAsset() {
        return operationalAsset;
    }

    public void setOperationalAsset(OperationalAsset operationalAsset) {
        this.operationalAsset = operationalAsset;
    }
}
