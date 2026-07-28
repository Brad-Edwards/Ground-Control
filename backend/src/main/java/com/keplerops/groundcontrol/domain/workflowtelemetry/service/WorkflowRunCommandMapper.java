package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import java.time.Instant;

/**
 * Applies a {@link RecordWorkflowRunCommand} onto a {@link WorkflowRun} (issue #859).
 *
 * <p>Its own type because the upsert is a merge, not an overwrite: a null field means "the emitter
 * did not observe this", so it must leave the stored value alone. That rule is easy to break by
 * adding a plain setter call, and keeping the mapping in one place is what makes it reviewable.
 */
final class WorkflowRunCommandMapper {

    private WorkflowRunCommandMapper() {}

    static void applyRunCommand(WorkflowRun run, RecordWorkflowRunCommand command) {
        // Merge semantics: apply each non-null field of the observation onto the run. setIfPresent
        // keeps this a flat data-driven mapping rather than a long if-chain.
        setIfPresent(command.repo(), run::setRepo);
        setIfPresent(command.issueNumber(), run::setIssueNumber);
        setIfPresent(command.prNumber(), run::setPrNumber);
        setIfPresent(command.branch(), run::setBranch);
        setIfPresent(command.runtimeDriver(), run::setRuntimeDriver);
        // Monotonic lifecycle fields (issue #1435). A run's start only ever moves earlier, a
        // terminal state is never reopened by a later or delayed observation, and an end time is
        // never cleared — otherwise a slow live write or a stale backfill would resurrect a
        // completed run and corrupt every active-run count and cycle-time percentile.
        run.setStartedAt(earliest(run.getStartedAt(), command.startedAt()));
        if (run.getFinalState() == null || !run.getFinalState().isTerminal()) {
            setIfPresent(command.endedAt(), run::setEndedAt);
            setIfPresent(command.finalState(), run::setFinalState);
            setIfPresent(command.outcome(), run::setOutcome);
        }
        setIfPresent(command.provenance(), run::setProvenance);
        setIfPresent(command.provider(), run::setProvider);
        setIfPresent(command.model(), run::setModel);
        setIfPresent(command.modelInvocationCount(), run::setModelInvocationCount);
        setIfPresent(command.wallClockMinutes(), run::setWallClockMinutes);
        setIfPresent(command.costProxy(), run::setCostProxy);
        setIfPresent(command.costCurrency(), run::setCostCurrency);
        setIfPresent(command.tokenUsage(), run::setTokenUsage);
        var uids = command.requirementUids();
        if (uids != null && !uids.isEmpty()) {
            run.setRequirementUids(uids);
        }
    }

    static <T> void setIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
