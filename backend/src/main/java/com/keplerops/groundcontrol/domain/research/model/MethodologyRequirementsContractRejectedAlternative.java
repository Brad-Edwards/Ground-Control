package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * GC-RSCH-N012 / ADR-079 §2 — a methodology alternative rejected in favour of the
 * contract's active selection. The rationale ledger stays the authority for
 * <em>why</em>: {@code rationaleEntryId} references a {@code METHODOLOGY_CHOICE}
 * {@link ResearchRunRationaleEntry} for the same run. An alternative not present
 * in the backend catalog is marked {@code external} and carries only a bounded
 * {@code methodKey} / {@code profileVersion} label. Written once with the
 * contract; not separately audited.
 */
@Entity
@Table(name = "methodology_requirements_contract_rejected_alternative")
public class MethodologyRequirementsContractRejectedAlternative extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private MethodologyRequirementsContract contract;

    @Column(name = "rationale_entry_id")
    private UUID rationaleEntryId;

    @Column(name = "method_key", nullable = false, length = 200)
    private String methodKey;

    @Column(name = "profile_version", length = 100)
    private String profileVersion;

    @Column(nullable = false)
    private boolean external;

    protected MethodologyRequirementsContractRejectedAlternative() {
        // JPA
    }

    public MethodologyRequirementsContractRejectedAlternative(
            MethodologyRequirementsContract contract,
            UUID rationaleEntryId,
            String methodKey,
            String profileVersion,
            boolean external) {
        this.contract = contract;
        this.rationaleEntryId = rationaleEntryId;
        this.methodKey = methodKey;
        this.profileVersion = profileVersion;
        this.external = external;
    }

    public MethodologyRequirementsContract getContract() {
        return contract;
    }

    public UUID getRationaleEntryId() {
        return rationaleEntryId;
    }

    public String getMethodKey() {
        return methodKey;
    }

    public String getProfileVersion() {
        return profileVersion;
    }

    public boolean isExternal() {
        return external;
    }
}
