package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/workflow-runs/findings/{findingId}/disposition}.
 *
 * <p>The service refuses {@code OPEN} here: this endpoint records a decision, and "still open" is
 * the absence of one. A finding is reopened by nothing — terminal dispositions are final so two
 * sources cannot silently overwrite each other's verdict.
 */
public record RecordFindingDispositionRequest(@NotNull FindingDisposition disposition) {}
