package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One finding in a station attempt's batch (issue #1355).
 *
 * <p>The request shape is where prose exclusion is enforced at the boundary: there is no property
 * for a title, body, remediation text, path, or line, so an emitter cannot send them even by
 * mistake, and the projection cannot become a rival to the ADR-029 record.
 *
 * <p>{@code category}, {@code severity}, and {@code classification} are optional because a source
 * that cannot attest one must omit it rather than have a value invented for it.
 */
public record GateFindingRequest(
        @NotBlank @Size(max = 200) String findingKey,
        @NotNull FindingSourceKind sourceKind,
        @NotBlank @Size(max = 100) String sourceId,
        @Size(max = 300) String category,
        @Size(max = 60) String severity,
        @Size(max = 20) String classification) {}
