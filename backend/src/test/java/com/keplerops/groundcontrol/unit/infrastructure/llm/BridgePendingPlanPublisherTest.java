package com.keplerops.groundcontrol.unit.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationObservation;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationRequest;
import com.keplerops.groundcontrol.infrastructure.llm.BridgePendingPlanPublisher;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Production {@link com.keplerops.groundcontrol.domain.llm.PlanPublicationPort} fails closed until
 * the ADR-081 bridge (#1281) adapts {@code gc_post_implementation_plan} semantics for Java callers.
 * Publication is unavailable, never silently replaced by a direct {@code gh} call.
 */
class BridgePendingPlanPublisherTest {

    private final BridgePendingPlanPublisher publisher = new BridgePendingPlanPublisher();

    @Test
    void publishFailsClosedWithAServiceUnavailableException() {
        var request =
                new PlanPublicationRequest("ground-control", 1280, List.of(), "issue-1280:plan", sampleCompletion());

        assertThatThrownBy(() -> publisher.publish(request))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("#1281");
    }

    /**
     * The observe half fails closed too, and it runs before inference — so a bridge-pending deployment
     * refuses an LLM-backed start without ever paying a provider.
     */
    @Test
    void findExistingPlanFailsClosedWithAServiceUnavailableException() {
        var observation = new PlanPublicationObservation("ground-control", 1280, "issue-1280:plan");

        assertThatThrownBy(() -> publisher.findExistingPlan(observation))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("#1281");
    }

    private static LlmCompletion sampleCompletion() {
        return new LlmCompletion("plan text", 10, 20);
    }
}
