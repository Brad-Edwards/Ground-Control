package com.keplerops.groundcontrol.domain.documents.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A polymorphic content block inside a document section.
 *
 * <p>Uniqueness: at most one {@code REQUIREMENT}-typed block may appear per section,
 * enforced by the partial unique index {@code uq_section_content_requirement}
 * on {@code (requirement_id) WHERE content_type = 'REQUIREMENT'} defined in V022.
 * JPA {@code @UniqueConstraint} cannot express a conditional (partial) index;
 * Flyway is the authority.
 */
@Entity
@Table(name = "section_content")
public class SectionContent extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "requirement_id")
    private Requirement requirement;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected SectionContent() {
        // JPA
    }

    public SectionContent(
            Section section, ContentType contentType, Requirement requirement, String textContent, int sortOrder) {
        this.section = section;
        this.contentType = contentType;
        this.requirement = requirement;
        this.textContent = textContent;
        this.sortOrder = sortOrder;
    }

    public Section getSection() {
        return section;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public Requirement getRequirement() {
        return requirement;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    @Override
    public String toString() {
        return contentType + ":" + (requirement != null ? requirement.getUid() : "text");
    }
}
