package com.keplerops.groundcontrol.domain.derivation.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "boundary_model_boundary")
public class BoundaryModelBoundary extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private BoundaryModelSnapshot snapshot;

    @Column(name = "boundary_key", nullable = false, length = 120)
    private String boundaryKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    private String source;

    @Convert(converter = StringListConverter.class)
    @Column(name = "path_selectors", nullable = false, columnDefinition = "TEXT")
    private List<String> pathSelectors;

    @Convert(converter = StringListConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<String> surfaces;

    @Convert(converter = StringListConverter.class)
    @Column(name = "input_fact_keys", nullable = false, columnDefinition = "TEXT")
    private List<String> inputFactKeys;

    protected BoundaryModelBoundary() {
        // JPA
    }

    public BoundaryModelBoundary(
            Project project,
            BoundaryModelSnapshot snapshot,
            String boundaryKey,
            String displayName,
            String description,
            String source,
            List<String> pathSelectors,
            List<String> surfaces,
            List<String> inputFactKeys) {
        this.project = project;
        this.snapshot = snapshot;
        this.boundaryKey = boundaryKey;
        this.displayName = displayName;
        this.description = description;
        this.source = source;
        this.pathSelectors = List.copyOf(pathSelectors);
        this.surfaces = List.copyOf(surfaces);
        this.inputFactKeys = List.copyOf(inputFactKeys);
    }

    public Project getProject() {
        return project;
    }

    public BoundaryModelSnapshot getSnapshot() {
        return snapshot;
    }

    public String getBoundaryKey() {
        return boundaryKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getSource() {
        return source;
    }

    public List<String> getPathSelectors() {
        return pathSelectors == null ? List.of() : List.copyOf(pathSelectors);
    }

    public List<String> getSurfaces() {
        return surfaces == null ? List.of() : List.copyOf(surfaces);
    }

    public List<String> getInputFactKeys() {
        return inputFactKeys == null ? List.of() : List.copyOf(inputFactKeys);
    }
}
