package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * GC-RSCH-F007 / GC-RSCH-R002 / ADR-079 §3 — grounds a {@link
 * MethodologyRequirementsContractEntry} in a {@link ResearchRunMethodologySource}
 * that supports it. The linked source must belong to the same run's active
 * methodology selection and be in {@code READ} state; the service rejects a claim
 * with no READ source link (no model memory as scientific evidence). {@code
 * locator} is a bounded artifact-relative anchor (section / page / source-local
 * anchor), never source text.
 */
@Entity
@Table(
        name = "methodology_requirements_contract_entry_source_link",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_contract_entry_source",
                        columnNames = {"entry_id", "source_id"}))
public class MethodologyRequirementsContractEntrySourceLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private MethodologyRequirementsContractEntry entry;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private ResearchRunMethodologySource source;

    @Column(length = 500)
    private String locator;

    protected MethodologyRequirementsContractEntrySourceLink() {
        // JPA
    }

    public MethodologyRequirementsContractEntrySourceLink(
            MethodologyRequirementsContractEntry entry, ResearchRunMethodologySource source, String locator) {
        this.entry = entry;
        this.source = source;
        this.locator = locator;
    }

    public MethodologyRequirementsContractEntry getEntry() {
        return entry;
    }

    public ResearchRunMethodologySource getSource() {
        return source;
    }

    public String getLocator() {
        return locator;
    }
}
