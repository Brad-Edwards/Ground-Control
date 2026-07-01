package com.keplerops.groundcontrol.api.controlidentification;

import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingConfirmation;
import java.util.UUID;

/** API response for a confirmed threat→control mapping (GC-GRC-008). */
public record ConfirmControlMappingResponse(
        UUID riskControlMappingId, UUID threatModelLinkId, boolean mappingCreated, boolean linkCreated) {

    public static ConfirmControlMappingResponse from(ControlMappingConfirmation confirmation) {
        return new ConfirmControlMappingResponse(
                confirmation.riskControlMappingId(),
                confirmation.threatModelLinkId(),
                confirmation.mappingCreated(),
                confirmation.linkCreated());
    }
}
