package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/workflow-runs/findings/{findingId}/disposition}.
 *
 * <p>The service refuses {@code OPEN} here: this endpoint records a decision, and "still open" is
 * the absence of one. A finding is reopened by nothing — terminal dispositions are final so two
 * sources cannot silently overwrite each other's verdict.
 *
 * <p>{@code authorizationReference} points at where the decision was recorded, normally the ADR-029
 * issue thread comment. It is required for the dispositions that retire a finding without fixing it
 * and refused for {@code FIXED}, which the station's next attempt evidences on its own. The rule is
 * enforced on the entity, not here, so it holds for every path into the field.
 *
 * @param disposition the terminal disposition to record
 * @param authorizationReference where closing without a fix was authorized; null for {@code FIXED}
 */
public record RecordFindingDispositionRequest(
        @NotNull FindingDisposition disposition, @Size(max = 500) String authorizationReference) {}
