package com.keplerops.groundcontrol.domain.llm;

/**
 * Resolves {@code (project, stage)} to a {@link ResolvedLlmRoute} from a trusted base/default-branch
 * {@code .ground-control.yaml} snapshot (ADR-027/ADR-028; ADR-036 stage vocabulary). This port never
 * parses YAML itself — that stays the exclusive job of the MCP {@code normalizeRoutingConfig} /
 * {@code resolveWorkflowRouteFromConfig} parser (ADR-027) — and it never resolves against a mutable
 * feature-worktree config: a change under implementation must not be able to redirect its own
 * source/context to another provider or increase its model spend.
 *
 * <p>Until the ADR-081 normalized-config handoff bridge (#1281) lands, the only production
 * implementation fails closed with
 * {@link com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException}: a deliberate,
 * tested, secure production behavior, not a placeholder.
 */
public interface TrustedRouteResolver {

    /**
     * Resolve the route for {@code project} at {@code stage}. Throws
     * {@link com.keplerops.groundcontrol.domain.exception.DomainValidationException} for a controlled,
     * non-retryable rejection (disabled routing, unknown stage/provider, invalid model), and
     * {@link com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException} when the
     * trusted configuration snapshot cannot be resolved right now (retryable).
     */
    ResolvedLlmRoute resolve(String project, String stage);
}
