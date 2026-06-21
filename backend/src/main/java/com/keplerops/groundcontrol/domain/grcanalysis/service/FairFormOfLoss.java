package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * The six FAIR forms of loss defined by The Open Group Risk Taxonomy (O-RT),
 * Version 3.0.1, the authoritative FAIR standard. This is the canonical FAIR
 * loss-magnitude taxonomy and the basis for GC-T016's materiality decomposition
 * and stakeholder-effect classification.
 *
 * <p>Source: The Open Group Standard, "Risk Taxonomy (O-RT)", Version 3.0.1,
 * Section "Forms of Loss" — "Six forms of loss are defined within this document":
 * Productivity, Response, Replacement, Fines and Judgments, Competitive
 * Advantage, and Reputation.
 *
 * <p>Per O-RT, the forms map to Primary vs Secondary Loss by tendency, not
 * strict assignment: productivity, response, and replacement are generally
 * experienced as Primary Loss; response, fines and judgments, competitive
 * advantage, and reputation are most commonly associated with Secondary Loss
 * (response can be either). That tendency is intentionally NOT encoded as data
 * here, because the standard treats it as contextual.
 *
 * <p>Each constant binds to the snake_case key used in the methodology-defined
 * input vocabulary so the opaque keys stay verbatim (ADR-034 / ADR-035
 * enum-mirror policy).
 */
public enum FairFormOfLoss {
    PRODUCTIVITY("productivity"),
    RESPONSE("response"),
    REPLACEMENT("replacement"),
    FINES_AND_JUDGMENTS("fines_and_judgments"),
    COMPETITIVE_ADVANTAGE("competitive_advantage"),
    REPUTATION("reputation");

    private final String jsonKey;

    FairFormOfLoss(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** The methodology-defined snake_case key for this form of loss. */
    public String jsonKey() {
        return jsonKey;
    }

    /**
     * Resolves a snake_case {@code loss_form} key to its O-RT form of loss.
     *
     * @param key snake_case key (e.g. {@code "reputation"})
     * @return the matching form, or {@code null} when {@code key} is null/blank or unknown
     */
    public static FairFormOfLoss fromJsonKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (FairFormOfLoss form : values()) {
            if (form.jsonKey.equals(key)) {
                return form;
            }
        }
        return null;
    }
}
