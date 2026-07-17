package com.keplerops.groundcontrol.domain.documents.model;

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

/**
 * {@code @Audited} per ADR-084 §5 (issue #1309): {@code Document} is a mutable, project-scoped
 * aggregate that feeds the AGE graph projection ({@code DocumentGraphProjectionContributor}).
 * Recording a graph snapshot's {@code source_revision} while this entity stayed unaudited would
 * make the snapshot's revision claim false for documents — an edit could change graph contents
 * without advancing any revision. {@code Project} is {@code @NotAudited} (it is itself unaudited)
 * per the same convention used across every other audited aggregate's owning-project reference.
 */
@Entity
@Audited
@Table(name = "document")
public class Document extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String grammar;

    @Column(length = 100)
    private String createdBy;

    protected Document() {
        // JPA
    }

    public Document(Project project, String title, String version, String description, String createdBy) {
        this.project = project;
        this.title = title;
        this.version = version;
        this.description = description;
        this.createdBy = createdBy;
    }

    public Project getProject() {
        return project;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGrammar() {
        return grammar;
    }

    public void setGrammar(String grammar) {
        this.grammar = grammar;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    @Override
    public String toString() {
        return title;
    }
}
