package com.keplerops.groundcontrol.domain.derivation.state;

public enum SystemModelFactKind {
    COMPONENT,
    TRUST_BOUNDARY,
    DATA_FLOW,
    ENTRY_POINT,
    TAINT_PATH,
    SECRET_USAGE,
    EXTERNAL_INTERACTION,
    DATA_CLASSIFICATION_HINT
}
