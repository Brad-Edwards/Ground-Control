package com.keplerops.groundcontrol.infrastructure.llm;

import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.ResolvedLlmRoute;
import com.keplerops.groundcontrol.domain.llm.TrustedRouteResolver;
import org.springframework.stereotype.Component;

/**
 * The production {@link TrustedRouteResolver} until the ADR-081 normalized-config handoff bridge
 * (#1281) lands. Fails closed with a stable, controlled {@link ServiceUnavailableException} rather
 * than trusting a mutable feature-branch config, inventing a Java-side YAML parser (ADR-027 reserves
 * that to the MCP tools), or silently selecting a process-global default provider/model.
 *
 * <p>This is deliberate, tested, secure production behavior — the preflight's explicit prescription
 * that an LLM-backed start remains fail-closed until #1281's bridge exists — not a placeholder left
 * unfinished.
 */
@Component
public class BridgePendingRouteResolver implements TrustedRouteResolver {

    static final String CODE = "llm_route_bridge_unavailable";

    @Override
    public ResolvedLlmRoute resolve(String project, String stage) {
        throw new ServiceUnavailableException(
                "Trusted LLM route resolution is not available until the ADR-081 normalized-config bridge (#1281)"
                        + " lands; code=" + CODE);
    }
}
