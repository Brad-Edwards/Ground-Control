package com.keplerops.groundcontrol.api.controlidentification;

import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to confirm a candidate control as a mitigation of a threat (GC-GRC-008 clause c). Records the
 * relationship through both canonical mapping aggregates. {@code controlRole} is optional (defaults to
 * {@code PREVENTIVE}); {@code mappingObjective} / {@code mappingScope} are optional context.
 */
public record ConfirmControlMappingRequest(
        @NotNull UUID threatModelId,
        @NotNull UUID controlId,
        MappingControlRole controlRole,
        String mappingObjective,
        String mappingScope) {}
