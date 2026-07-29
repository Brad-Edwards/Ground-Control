package com.keplerops.groundcontrol.api.session;

import java.util.List;

/**
 * Credential-free current-principal read for the console shell (GC-Q015 clause (a)).
 *
 * <p>Carries only display identity and server-derived presentation hints. It is deliberately not
 * the admin {@code UserResponse}, an identity entity, or a decoded session/CSRF value: no session
 * id, credential metadata, or authentication detail is exposed. {@code roles} is a compatibility
 * projection for display during the ADR-085 migration and {@code canAdminister} is a UX affordance
 * hint only — authorization remains enforced by {@code ApiPathMatrix} and the service layer, never
 * by this payload.
 */
public record SessionResponse(String displayName, List<String> roles, boolean canAdminister) {}
