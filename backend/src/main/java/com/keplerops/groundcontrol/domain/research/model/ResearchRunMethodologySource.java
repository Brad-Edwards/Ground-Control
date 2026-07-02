package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
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
 * GC-RSCH-F006 — one source entry within a {@link
 * ResearchRunMethodologySelection}. A source is identified by its
 * {@code sourceRef} (e.g. a Zotero key, DOI, or stable URL); the pair
 * {@code (selection_id, source_ref)} is unique. Sources start in
 * {@code ATTEMPTED} state; only {@code required} sources must reach
 * {@code READ} before the coverage gate opens.
 */
@Entity
@Audited
@Table(
        name = "research_run_methodology_source",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_methodology_source_ref",
                        columnNames = {"selection_id", "source_ref"}))
public class ResearchRunMethodologySource extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "selection_id", nullable = false)
    private ResearchRunMethodologySelection selection;

    @Column(name = "source_ref", nullable = false, length = 500)
    private String sourceRef;

    @Column(name = "source_label", length = 500)
    private String sourceLabel;

    @Column(nullable = false)
    private boolean required;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MethodologySourceState state;

    @Column(length = 200)
    private String actor;

    protected ResearchRunMethodologySource() {
        // JPA
    }

    public ResearchRunMethodologySource(
            ResearchRunMethodologySelection selection, String sourceRef, boolean required, String actor) {
        this.selection = selection;
        this.sourceRef = sourceRef;
        this.required = required;
        this.state = MethodologySourceState.ATTEMPTED;
        this.actor = actor;
    }

    public ResearchRunMethodologySelection getSelection() {
        return selection;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public boolean isRequired() {
        return required;
    }

    public MethodologySourceState getState() {
        return state;
    }

    public void setState(MethodologySourceState state) {
        this.state = state;
    }

    public String getActor() {
        return actor;
    }
}
