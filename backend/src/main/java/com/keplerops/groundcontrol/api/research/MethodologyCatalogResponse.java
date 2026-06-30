package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.MethodProfile;
import java.util.List;

/**
 * GC-RSCH-F006 / ADR-077 — read view of the backend-owned methodology catalog:
 * the catalog version plus every method profile with its required primary
 * sources. Returned by {@code GET /api/v1/research-runs/methodology/catalog}.
 */
public record MethodologyCatalogResponse(String catalogVersion, List<MethodologyCatalogMethodResponse> methods) {

    public static MethodologyCatalogResponse from(List<MethodProfile> profiles) {
        var catalogVersion = profiles.isEmpty() ? null : profiles.get(0).catalogVersion();
        var methods =
                profiles.stream().map(MethodologyCatalogMethodResponse::from).toList();
        return new MethodologyCatalogResponse(catalogVersion, methods);
    }

    /** One method profile within the catalog. */
    public record MethodologyCatalogMethodResponse(
            String methodKey,
            String label,
            String profileVersion,
            String catalogVersion,
            List<MethodologyCatalogSourceResponse> requiredSources) {

        public static MethodologyCatalogMethodResponse from(MethodProfile profile) {
            return new MethodologyCatalogMethodResponse(
                    profile.methodKey(),
                    profile.label(),
                    profile.profileVersion(),
                    profile.catalogVersion(),
                    profile.requiredSources().stream()
                            .map(MethodologyCatalogSourceResponse::from)
                            .toList());
        }
    }

    /** One required primary source within a method profile. */
    public record MethodologyCatalogSourceResponse(String ref, String title) {

        public static MethodologyCatalogSourceResponse from(
                com.keplerops.groundcontrol.domain.research.model.MethodProfileSource source) {
            return new MethodologyCatalogSourceResponse(source.ref(), source.title());
        }
    }
}
