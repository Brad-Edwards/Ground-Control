package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * FAIR-CAM control domains per GC-I017. Classifies controls by their primary
 * function in the FAIR-CAM framework.
 */
public enum FairCamControlDomain {
    LOSS_EVENT_CONTROL("loss_event_control"),
    VARIANCE_MANAGEMENT_CONTROL("variance_management_control"),
    DECISION_SUPPORT_CONTROL("decision_support_control");

    private final String jsonKey;

    FairCamControlDomain(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    public String jsonKey() {
        return jsonKey;
    }

    public static FairCamControlDomain fromJsonKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (FairCamControlDomain domain : values()) {
            if (domain.jsonKey.equals(key)) {
                return domain;
            }
        }
        return null;
    }
}
