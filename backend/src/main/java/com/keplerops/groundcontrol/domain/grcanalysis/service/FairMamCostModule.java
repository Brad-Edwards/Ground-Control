package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * The ten primary cost modules of the FAIR Materiality Assessment Model
 * (FAIR-MAM), the FAIR Institute's extension that expands the loss-magnitude
 * side of FAIR into a more granular, cyber-loss-specific taxonomy for
 * materiality analysis (GC-T016's "FAIR-MAM-aligned extensions" clause).
 *
 * <p>Source: FAIR Institute / Safe Security, "FAIR Materiality Assessment Model
 * (FAIR-MAM)". FAIR-MAM is composed of ten primary cost modules, each tagged
 * with the FAIR Primary/Secondary loss attributes and refined through three to
 * five further layers of subcategories. Only the ten top-level modules are
 * modelled here; the deeper subcategory layers are part of the gated FAIR-MAM
 * white paper and are intentionally not reproduced. Distinct from the base
 * {@link FairFormOfLoss} (O-RT six forms of loss).
 *
 * <p>Each constant binds to the snake_case key used in the methodology-defined
 * {@code fair_mam} input map so the opaque key vocabulary stays verbatim
 * (ADR-034 / ADR-035 enum-mirror policy).
 */
public enum FairMamCostModule {
    INFORMATION_PRIVACY("information_privacy"),
    PROPRIETARY_DATA_LOSS("proprietary_data_loss"),
    BUSINESS_INTERRUPTION("business_interruption"),
    CYBER_EXTORTION("cyber_extortion"),
    NETWORK_SECURITY("network_security"),
    FINANCIAL_FRAUD("financial_fraud"),
    MEDIA_CONTENT("media_content"),
    HARDWARE_BRICKING("hardware_bricking"),
    POST_BREACH_SECURITY_IMPROVEMENTS("post_breach_security_improvements"),
    REPUTATIONAL_DAMAGE("reputational_damage");

    private final String jsonKey;

    FairMamCostModule(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** The methodology-defined {@code fair_mam} snake_case key for this cost module. */
    public String jsonKey() {
        return jsonKey;
    }

    /**
     * Resolves a {@code fair_mam} snake_case key to its FAIR-MAM cost module.
     *
     * @param key snake_case key (e.g. {@code "business_interruption"})
     * @return the matching module, or {@code null} when {@code key} is null/blank or unknown
     */
    public static FairMamCostModule fromJsonKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (FairMamCostModule module : values()) {
            if (module.jsonKey.equals(key)) {
                return module;
            }
        }
        return null;
    }
}
