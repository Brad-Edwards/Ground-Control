package com.keplerops.groundcontrol.unit.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.infrastructure.llm.BridgePendingRouteResolver;
import org.junit.jupiter.api.Test;

/**
 * Production {@link com.keplerops.groundcontrol.domain.llm.TrustedRouteResolver} fails closed until
 * the ADR-081 normalized-config handoff bridge (#1281) lands. This is deliberate secure production
 * behavior — the preflight's explicit prescription that LLM-backed starts remain fail-closed — not a
 * stub awaiting an implementation.
 */
class BridgePendingRouteResolverTest {

    private final BridgePendingRouteResolver resolver = new BridgePendingRouteResolver();

    @Test
    void resolveFailsClosedWithAServiceUnavailableException() {
        assertThatThrownBy(() -> resolver.resolve("ground-control", "planning"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("#1281");
    }

    @Test
    void resolveFailsClosedRegardlessOfInputs() {
        assertThatThrownBy(() -> resolver.resolve(null, null)).isInstanceOf(ServiceUnavailableException.class);
    }
}
