package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * The six FAIR forms of loss per The Open Group Risk Taxonomy (O-RT) standard,
 * the canonical FAIR loss vocabulary referenced by GC-T016's "FAIR loss forms"
 * clause. These are the base FAIR taxonomy — distinct from the more granular
 * {@link FairMamCostModule} (FAIR-MAM) cost modules, which expand the loss
 * magnitude side of the model.
 *
 * <p>Source: The Open Group, "Risk Taxonomy (O-RT)" — losses manifest as
 * Productivity, Response, Replacement, Fines and Judgments, Competitive
 * Advantage, and Reputation. Each constant binds to the snake_case key used in
 * the methodology-defined input vocabulary so the opaque keys stay verbatim
 * (ADR-034 / ADR-035 enum-mirror policy).
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
