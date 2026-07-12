package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CodexReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CodexReviewResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.FinalReportInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.FinalReportResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementChangeInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementChangeResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReadinessRecordInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReadinessRecordResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.TestQualityReviewResult;

/**
 * Implements the published {@link ImplementContentActivities} seam by composing collaborators, so the
 * LLM provider dependency reaches only {@code authorPlan} — never the other content-activity methods,
 * and never the deterministic {@link ImplementActivities} (enforced by
 * {@code ArchitectureLlmBoundaryTest}).
 *
 * <p>Only {@code authorPlan} is in scope for issue #1280 (GC-O009 phase 5, the LLM provider boundary):
 * it delegates to {@link LlmPlanAuthor}, the one collaborator that holds the provider registry. The
 * remaining seams — {@code implementChange} (TDD change implementation), the codex/test-quality
 * reviews, and the durable readiness/final-report record posting — are deterministic-record-publication
 * concerns owned by the ADR-081 bridge phase (#1281) and later program phases; this class fails closed
 * for them with a stable {@link ServiceUnavailableException} rather than recreating a weaker
 * implementation or silently no-op'ing. Not a Spring bean, mirroring {@link ImplementActivitiesImpl}:
 * constructed explicitly by tests today, by the worker configuration once every collaborator's
 * production adapters land.
 */
public final class ImplementContentActivitiesImpl implements ImplementContentActivities {

    private final LlmPlanAuthor llmPlanAuthor;

    public ImplementContentActivitiesImpl(LlmPlanAuthor llmPlanAuthor) {
        this.llmPlanAuthor = llmPlanAuthor;
    }

    @Override
    public AuthorPlanResult authorPlan(AuthorPlanInput input) {
        return llmPlanAuthor.authorPlan(input);
    }

    @Override
    public ImplementChangeResult implementChange(ImplementChangeInput input) {
        throw notYetAvailable("implementChange");
    }

    @Override
    public CodexReviewResult runCodexReview(CodexReviewInput input) {
        throw notYetAvailable("runCodexReview");
    }

    @Override
    public TestQualityReviewResult runTestQualityReview(TestQualityReviewInput input) {
        throw notYetAvailable("runTestQualityReview");
    }

    @Override
    public ReadinessRecordResult postReadinessRecord(ReadinessRecordInput input) {
        throw notYetAvailable("postReadinessRecord");
    }

    @Override
    public FinalReportResult postFinalReport(FinalReportInput input) {
        throw notYetAvailable("postFinalReport");
    }

    private static ServiceUnavailableException notYetAvailable(String activityMethod) {
        return new ServiceUnavailableException(activityMethod
                + " is not available until the ADR-081 bridge (#1281) adapts durable record publication for Java"
                + " callers");
    }
}
