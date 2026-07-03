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

/**
 * GC-RSCH-F009 / ADR-083 §3 — one method-specific output section of a {@link
 * ProtocolPlan}. {@code sectionKind} fixes the semantic class; {@code
 * sectionKey} is a stable key unique per plan (several sections may share a
 * kind, e.g. multiple {@link ProtocolSectionKind#SOURCE_ROLES} sections for
 * the taxonomy-development method, one per {@link ProtocolSourceRole}). Rows
 * are written once with the plan (immutable snapshot), so they are not
 * separately audited. {@code sourceRole} is non-null only for {@code
 * SOURCE_ROLES} sections on the taxonomy-development method (ADR-083 §3).
 */
@Entity
@Table(
        name = "protocol_plan_section",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_protocol_plan_section_key",
                        columnNames = {"protocol_plan_id", "section_key"}))
public class ProtocolPlanSection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "protocol_plan_id", nullable = false)
    private ProtocolPlan protocolPlan;

    @Column(name = "section_key", nullable = false, length = 200)
    private String sectionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "section_kind", nullable = false, length = 60)
    private ProtocolSectionKind sectionKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_role", length = 40)
    private ProtocolSourceRole sourceRole;

    @Column(name = "content_summary", nullable = false, length = 2000)
    private String contentSummary;

    @Column(length = 200)
    private String actor;

    protected ProtocolPlanSection() {
        // JPA
    }

    public ProtocolPlanSection(
            ProtocolPlan protocolPlan,
            String sectionKey,
            ProtocolSectionKind sectionKind,
            ProtocolSourceRole sourceRole,
            String contentSummary,
            String actor) {
        this.protocolPlan = protocolPlan;
        this.sectionKey = sectionKey;
        this.sectionKind = sectionKind;
        this.sourceRole = sourceRole;
        this.contentSummary = contentSummary;
        this.actor = actor;
    }

    public ProtocolPlan getProtocolPlan() {
        return protocolPlan;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public ProtocolSectionKind getSectionKind() {
        return sectionKind;
    }

    public ProtocolSourceRole getSourceRole() {
        return sourceRole;
    }

    public String getContentSummary() {
        return contentSummary;
    }

    public String getActor() {
        return actor;
    }
}
