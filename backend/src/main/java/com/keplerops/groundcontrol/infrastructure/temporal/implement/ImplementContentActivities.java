package com.keplerops.groundcontrol.infrastructure.temporal.implement;

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
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Seam interface for the content-producing steps of the {@code /implement} A-E graph: LLM plan
 * authoring, TDD change implementation, the codex and test-quality reviews, and the issue-thread
 * readiness/final-report records rendered by the existing ADR-029/ADR-036 MCP surfaces.
 *
 * <p><strong>No implementation ships in phase 2 (issue #1277) by design.</strong> These activities are
 * sequenced by {@link ImplementWorkflowImpl} so the deterministic gate graph is complete and
 * end-to-end testable (the Temporal test environment registers deterministic doubles), but their
 * concrete implementations are owned by later program phases: the LLM-backed activities by #1280 and
 * the issue-thread record posting by the bridge phase #1281 (ADR-081). Their I/O contracts
 * ({@code contracts/schemas/workflow/content-activities.v1}) are published here, contract-first.
 * Only bounded results (posted flags, ids, verdicts, counts) cross the Temporal boundary — never plan
 * prose, code, review transcripts, prompts, or completions (ADR-028 redaction rule).
 */
@ActivityInterface
public interface ImplementContentActivities {

    @ActivityMethod
    AuthorPlanResult authorPlan(AuthorPlanInput input);

    @ActivityMethod
    ImplementChangeResult implementChange(ImplementChangeInput input);

    @ActivityMethod
    CodexReviewResult runCodexReview(CodexReviewInput input);

    @ActivityMethod
    TestQualityReviewResult runTestQualityReview(TestQualityReviewInput input);

    @ActivityMethod
    ReadinessRecordResult postReadinessRecord(ReadinessRecordInput input);

    @ActivityMethod
    FinalReportResult postFinalReport(FinalReportInput input);
}
