package com.keplerops.groundcontrol.infrastructure.temporal.implement.port;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CompletionGateResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GitPublishResult;

/**
 * Infrastructure port for the local workspace side effects the deterministic {@code /implement}
 * activities drive: running the configured completion command and staging/committing/pushing with the
 * pre-commit retry loop. Interface only in phase 2 (issue #1277); the process-executing adapter uses
 * argv arrays and sanitized inputs and lands with the bridge phase (#1281).
 *
 * <p>{@code stageCommitPush} takes a stable {@code idempotencyKey}: Temporal executes activities
 * at-least-once, so the adapter MUST observe-before-create (skip when the commit for that key already
 * exists on the branch) rather than producing a duplicate commit on retry.
 */
public interface WorkspacePort {

    /** Run the configured completion command; returns pass/fail, exit code, and a bounded redacted summary. */
    CompletionGateResult runCompletionGate(String command);

    /** Stage, run pre-commit hooks (bounded retry), commit, and push; idempotent by {@code idempotencyKey}. */
    GitPublishResult stageCommitPush(
            String branch, String commitMessage, int maxPrecommitRetries, String idempotencyKey);
}
