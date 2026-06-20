package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * The six canonical FAIR forms of loss (FAIR taxonomy / FAIR-MAM loss-magnitude
 * breakdown) per GC-T016. Each constant binds to the snake_case key used inside
 * the methodology-defined {@code fair_mam} input map so the opaque vocabulary
 * stays verbatim (ADR-034 / ADR-035 enum-mirror policy) while the analysis
 * envelope can express loss forms as a typed dimension rather than free strings.
 */
public enum FairLossForm {
    PRODUCTIVITY("productivity_loss"),
    RESPONSE("response_cost"),
    REPLACEMENT("replacement_cost"),
    COMPETITIVE_ADVANTAGE("competitive_advantage_loss"),
    FINES_AND_JUDGMENTS("fines_and_judgments"),
    REPUTATION("reputation_damage");

    private final String jsonKey;

    FairLossForm(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** The methodology-defined {@code fair_mam} snake_case key for this loss form. */
    public String jsonKey() {
        return jsonKey;
    }

    /**
     * Resolves a {@code fair_mam} / stakeholder {@code loss_form} key to its loss form.
     *
     * @param key snake_case key (e.g. {@code "reputation_damage"})
     * @return the matching loss form, or {@code null} when {@code key} is null/blank or unknown
     */
    public static FairLossForm fromJsonKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (FairLossForm form : values()) {
            if (form.jsonKey.equals(key)) {
                return form;
            }
        }
        return null;
    }
}
