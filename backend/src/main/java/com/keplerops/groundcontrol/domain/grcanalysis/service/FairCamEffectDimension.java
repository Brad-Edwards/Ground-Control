package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * FAIR-CAM effect dimensions per GC-I017. Captures the dimensions through which
 * a control influences risk factors in the FAIR-CAM framework.
 */
public enum FairCamEffectDimension {
    LOSS_EVENT_FREQUENCY("loss_event_frequency"),
    LOSS_MAGNITUDE("loss_magnitude"),
    CONTROL_RELIABILITY("control_reliability"),
    DECISION_ALIGNMENT("decision_alignment");

    private final String jsonKey;

    FairCamEffectDimension(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    public String jsonKey() {
        return jsonKey;
    }

    public static FairCamEffectDimension fromJsonKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (FairCamEffectDimension dim : values()) {
            if (dim.jsonKey.equals(key)) {
                return dim;
            }
        }
        return null;
    }
}
