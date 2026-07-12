package com.keplerops.groundcontrol.infrastructure.llm;

import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationObservation;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationPort;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationRequest;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationResult;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The production {@link PlanPublicationPort} until the ADR-081 bridge (#1281) adapts
 * {@code gc_post_implementation_plan} semantics (preflight/GRC prerequisites, GRC deliverable
 * coverage, reserved-marker rejection, {@code detectSensitiveBodyContent}, body bounds, the plan
 * phase marker) for Java callers. Fails closed with a stable, controlled
 * {@link ServiceUnavailableException} — publication is unavailable, never silently replaced by a
 * direct {@code gh}/{@code git}/{@code curl} call from the worker process.
 *
 * <p>Deliberate, tested, secure production behavior: successful LLM inference must never be counted
 * as a completed plan phase before durable publication actually succeeds.
 */
@Component
public class BridgePendingPlanPublisher implements PlanPublicationPort {

    static final String CODE = "llm_plan_publication_bridge_unavailable";

    /**
     * Fails closed exactly like {@link #publish}. This runs BEFORE inference, so the bridge-pending
     * posture costs nothing: an LLM-backed start is refused without ever paying a provider.
     */
    @Override
    public Optional<PlanPublicationResult> findExistingPlan(PlanPublicationObservation observation) {
        throw unavailable();
    }

    @Override
    public PlanPublicationResult publish(PlanPublicationRequest request) {
        throw unavailable();
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException(
                "LLM plan publication is not available until the ADR-081 bridge (#1281) adapts"
                        + " gc_post_implementation_plan for Java callers; code=" + CODE);
    }
}
