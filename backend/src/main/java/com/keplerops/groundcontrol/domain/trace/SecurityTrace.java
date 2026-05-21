package com.keplerops.groundcontrol.domain.trace;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import java.util.List;
import java.util.UUID;

/**
 * Composite read-model providing end-to-end traceability from a security
 * source (threat model or risk scenario) through its linked operational
 * assets, controls, and requirements, down to the per-requirement
 * implementing artifacts stored as {@link TraceabilityLink} rows.
 *
 * <p>Pure data record — no Spring web imports; no JPA entities; no side
 * effects. The domain layer produces this; the API layer maps it to
 * {@code SecurityTraceResponse}.
 */
public record SecurityTrace(
        SecurityTraceSourceType sourceType,
        UUID sourceId,
        String sourceUid,
        String sourceTitle,
        List<OperationalAsset> assets,
        List<Control> controls,
        List<RequirementTrace> requirements) {

    /**
     * Pairs a {@link Requirement} with the {@link TraceabilityLink} rows that
     * record its implementing artifacts (code, PRs, issues, configuration,
     * controls, etc.).
     */
    public record RequirementTrace(Requirement requirement, List<TraceabilityLink> artifacts) {}
}
