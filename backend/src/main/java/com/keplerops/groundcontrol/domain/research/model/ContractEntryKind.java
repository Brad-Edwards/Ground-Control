package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F007 / ADR-080 §3 — closed vocabulary for the semantic class of a
 * {@link MethodologyRequirementsContractEntry}. The set is API-visible and
 * follows ADR-034 enum-contract rules; extend it deliberately rather than
 * overloading an existing value.
 *
 * <ul>
 *   <li>{@link #REQUIREMENT} — a methodology-source-derived obligation the
 *       protocol must satisfy.</li>
 *   <li>{@link #METHOD_LIMIT} — a source-derived limit on what the selected
 *       method can claim (a scientific-humility fact, GC-RSCH-N016).</li>
 *   <li>{@link #NON_CLAIM} — an explicit boundary the method or artifact does
 *       not assert (GC-RSCH-N016).</li>
 *   <li>{@link #OPEN_PROTOCOL_QUESTION} — a question or gate phase 2 must answer,
 *       route to a user decision, or explicitly defer (GC-RSCH-F008).</li>
 * </ul>
 */
public enum ContractEntryKind {
    REQUIREMENT,
    METHOD_LIMIT,
    NON_CLAIM,
    OPEN_PROTOCOL_QUESTION;

    /**
     * Whether an entry of this kind must be grounded in at least one methodology
     * source link. {@code REQUIREMENT}, {@code METHOD_LIMIT}, and {@code NON_CLAIM}
     * are claims that require source evidence (GC-RSCH-R002); an
     * {@code OPEN_PROTOCOL_QUESTION} may instead reference another entry that
     * raises it.
     */
    public boolean requiresSourceGrounding() {
        return this != OPEN_PROTOCOL_QUESTION;
    }
}
