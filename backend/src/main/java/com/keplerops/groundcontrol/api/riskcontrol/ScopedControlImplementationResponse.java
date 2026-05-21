package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import java.time.Instant;
import java.util.UUID;

public record ScopedControlImplementationResponse(
        UUID id,
        String uid,
        UUID controlId,
        String controlUid,
        String name,
        String implementationScope,
        UUID operationalAssetId,
        Instant createdAt,
        Instant updatedAt) {

    public static ScopedControlImplementationResponse from(ScopedControlImplementation sci) {
        return new ScopedControlImplementationResponse(
                sci.getId(),
                sci.getUid(),
                sci.getControl().getId(),
                sci.getControl().getUid(),
                sci.getName(),
                sci.getImplementationScope(),
                sci.getOperationalAsset() != null ? sci.getOperationalAsset().getId() : null,
                sci.getCreatedAt(),
                sci.getUpdatedAt());
    }
}
