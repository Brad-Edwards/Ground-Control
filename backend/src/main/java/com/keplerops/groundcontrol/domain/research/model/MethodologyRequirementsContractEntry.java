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
 * GC-RSCH-F007 / ADR-080 §3 — one extracted entry within a {@link
 * MethodologyRequirementsContract}. Its {@link ContractEntryKind} fixes the
 * semantic class; {@code entryKey} is a stable key (unique per contract) that
 * protocol planning references to fill, gate, or defer the item. {@code
 * statement} is bounded free text; the backend does not parse it for domain
 * answers (ADR-080 §4). Entries are written once with the contract (immutable
 * snapshot), so they are not separately audited.
 */
@Entity
@Table(
        name = "methodology_requirements_contract_entry",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_contract_entry_key",
                        columnNames = {"contract_id", "entry_key"}))
public class MethodologyRequirementsContractEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private MethodologyRequirementsContract contract;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ContractEntryKind kind;

    @Column(name = "entry_key", nullable = false, length = 200)
    private String entryKey;

    @Column(nullable = false, length = 2000)
    private String statement;

    @Column(name = "references_entry_key", length = 200)
    private String referencesEntryKey;

    @Column(length = 200)
    private String actor;

    protected MethodologyRequirementsContractEntry() {
        // JPA
    }

    public MethodologyRequirementsContractEntry(
            MethodologyRequirementsContract contract,
            ContractEntryKind kind,
            String entryKey,
            String statement,
            String referencesEntryKey,
            String actor) {
        this.contract = contract;
        this.kind = kind;
        this.entryKey = entryKey;
        this.statement = statement;
        this.referencesEntryKey = referencesEntryKey;
        this.actor = actor;
    }

    public MethodologyRequirementsContract getContract() {
        return contract;
    }

    public ContractEntryKind getKind() {
        return kind;
    }

    public String getEntryKey() {
        return entryKey;
    }

    public String getStatement() {
        return statement;
    }

    public String getReferencesEntryKey() {
        return referencesEntryKey;
    }

    public String getActor() {
        return actor;
    }
}
