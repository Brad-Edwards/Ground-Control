package com.keplerops.groundcontrol.api.requirements;

import com.keplerops.groundcontrol.domain.requirements.service.RequirementWithLinks;
import java.util.List;

/**
 * One row of the traceability matrix (GC-Q003): a requirement and the
 * traceability links anchored to it. Links are pre-filtered to the requested
 * {@code linkType} by the service when a filter is supplied.
 */
public record RequirementWithLinksResponse(RequirementResponse requirement, List<TraceabilityLinkResponse> links) {

    public static RequirementWithLinksResponse from(RequirementWithLinks matrixRow) {
        return new RequirementWithLinksResponse(
                RequirementResponse.from(matrixRow.requirement()),
                matrixRow.links().stream().map(TraceabilityLinkResponse::from).toList());
    }
}
