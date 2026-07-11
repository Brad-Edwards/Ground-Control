package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import com.keplerops.groundcontrol.domain.llm.LlmProvider;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationObservation;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationPort;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationRequest;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationResult;
import com.keplerops.groundcontrol.infrastructure.llm.LlmProviderRegistry;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CodexReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.FinalReportInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementChangeInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReadinessRecordInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolvedLlmRoute;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ImplementContentActivitiesImpl} composes collaborators so the LLM provider dependency reaches
 * only {@code authorPlan} (architecture preflight). The other content-activity seams — issue-thread
 * record publication — are #1281's bridge scope and fail closed here, never silently replaced by a
 * weaker Java-side implementation.
 */
class ImplementContentActivitiesImplTest {

    private static final ResolvedLlmRoute ROUTE = new ResolvedLlmRoute(
            "v2", "ground-control", "planning", "high", "anthropic", "claude-opus-4-8", "digest-1");

    private ImplementContentActivitiesImpl activitiesWith(LlmProvider provider, PlanPublicationPort publisher) {
        var llmPlanAuthor = new LlmPlanAuthor(new LlmProviderRegistry(List.of(provider)), publisher);
        return new ImplementContentActivitiesImpl(llmPlanAuthor);
    }

    @Test
    void authorPlanDelegatesToTheComposedLlmPlanAuthor() {
        var provider = fakeProvider();
        var publisher = fakePublisher(new PlanPublicationResult(true, 7));
        var activities = activitiesWith(provider, publisher);

        var result =
                activities.authorPlan(new AuthorPlanInput("ground-control", ROUTE, 1280, List.of(), "issue-1280:plan"));

        assertThat(result.posted()).isTrue();
        assertThat(result.commentId()).isEqualTo(7);
    }

    @Test
    void implementChangeFailsClosedUntilTheBridgeLands() {
        var activities = activitiesWith(fakeProvider(), fakePublisher(new PlanPublicationResult(true, 1)));

        assertThatThrownBy(() -> activities.implementChange(new ImplementChangeInput(1280, null, "key")))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void runCodexReviewFailsClosedUntilTheBridgeLands() {
        var activities = activitiesWith(fakeProvider(), fakePublisher(new PlanPublicationResult(true, 1)));

        assertThatThrownBy(() -> activities.runCodexReview(new CodexReviewInput(1280, 1)))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void runTestQualityReviewFailsClosedUntilTheBridgeLands() {
        var activities = activitiesWith(fakeProvider(), fakePublisher(new PlanPublicationResult(true, 1)));

        assertThatThrownBy(() -> activities.runTestQualityReview(new TestQualityReviewInput(1280, 1)))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void postReadinessRecordFailsClosedUntilTheBridgeLands() {
        var activities = activitiesWith(fakeProvider(), fakePublisher(new PlanPublicationResult(true, 1)));

        assertThatThrownBy(() -> activities.postReadinessRecord(new ReadinessRecordInput(1280, 1, "key")))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void postFinalReportFailsClosedUntilTheBridgeLands() {
        var activities = activitiesWith(fakeProvider(), fakePublisher(new PlanPublicationResult(true, 1)));

        assertThatThrownBy(() -> activities.postFinalReport(new FinalReportInput(1280, 1, List.of(), "key")))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    private static LlmProvider fakeProvider() {
        return new LlmProvider() {
            @Override
            public LlmCompletion complete(LlmCompletionRequest request) {
                return new LlmCompletion("plan body", 1, 1);
            }

            @Override
            public String providerId() {
                return "anthropic";
            }
        };
    }

    private static PlanPublicationPort fakePublisher(PlanPublicationResult result) {
        return new PlanPublicationPort() {
            @Override
            public java.util.Optional<PlanPublicationResult> findExistingPlan(PlanPublicationObservation o) {
                return java.util.Optional.empty();
            }

            @Override
            public PlanPublicationResult publish(PlanPublicationRequest request) {
                return result;
            }
        };
    }
}
