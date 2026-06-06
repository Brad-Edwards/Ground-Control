package com.keplerops.groundcontrol.domain.projects.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "project")
public class Project extends BaseEntity {

    @Column(unique = true, nullable = false, length = 50, updatable = false)
    private String identifier;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectType type = ProjectType.SOFTWARE;

    protected Project() {
        // JPA
    }

    public Project(String identifier, String name) {
        this(identifier, name, ProjectType.SOFTWARE);
    }

    public Project(String identifier, String name, ProjectType type) {
        this.identifier = identifier;
        this.name = name;
        this.type = type == null ? ProjectType.SOFTWARE : type;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ProjectType getType() {
        return type;
    }

    @Override
    public String toString() {
        return identifier;
    }
}
