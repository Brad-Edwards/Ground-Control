package com.keplerops.groundcontrol.infrastructure.temporal.control;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase;
import com.keplerops.groundcontrol.domain.workflowexecution.Reviewer;
import com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowControlPort;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionId;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionRef;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionView;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowOutcome;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import com.keplerops.groundcontrol.domain.workflowexecution.service.SendSignalCommand;
import com.keplerops.groundcontrol.domain.workflowexecution.service.StartWorkflowCommand;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CancelSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.CapDisposition;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.GateState;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementOutcome;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementPhase;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ImplementWorkflowInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolvedLlmRoute;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.RetryFromSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewCapDispositionSignal;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ReviewerKind;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single Temporal-backed implementation of {@link WorkflowControlPort} (ADR-028: "one adapter";
 * {@code WorkflowClient}/Visibility touched nowhere else). Starts executions on the configured task
 * queue with project/issue/requirement ids in the Memo (non-secret correlation, read back for the
 * product read model), lists a project's executions from Temporal Visibility filtered by workflow-id
 * prefix, describes one execution, and sends the closed operator-signal catalog via untyped stubs.
 *
 * <p>Domain product enums are translated 1:1 to the Temporal-history contract enums here — the only
 * place the two contracts meet. The exhaustive mapping is guarded by
 * {@code TemporalWorkflowControlAdapterTest}.
 */
public class TemporalWorkflowControlAdapter implements WorkflowControlPort {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkflowControlAdapter.class);

    private static final String MEMO_PROJECT = "project";
    private static final String MEMO_ISSUE_NUMBER = "issueNumber";
    private static final String MEMO_REQUIREMENT_UIDS = "requirementUids";
    private static final String MEMO_DECODE_FAILURE_LOG = "Ignoring undecodable memo key {}";

    private final WorkflowClient workflowClient;
    private final TemporalControlProperties properties;

    public TemporalWorkflowControlAdapter(WorkflowClient workflowClient, TemporalControlProperties properties) {
        this.workflowClient = workflowClient;
        this.properties = properties;
    }

    @Override
    public WorkflowExecutionRef start(StartWorkflowCommand command) {
        var options = WorkflowOptions.newBuilder()
                .setWorkflowId(command.workflowId())
                .setTaskQueue(properties.taskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setMemo(memoFor(command))
                .build();
        var stub = workflowClient.newUntypedWorkflowStub(temporalTypeName(command.workflowType()), options);
        try {
            var execution = stub.start(inputFor(command));
            return new WorkflowExecutionRef(
                    execution.getWorkflowId(), execution.getRunId(), command.workflowType(), command.project());
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            throw new ConflictException("Workflow execution already running: " + command.workflowId());
        }
    }

    @Override
    public List<WorkflowExecutionView> listForProject(String project, int limit) {
        // Scope the project filter into the Temporal Visibility query (WorkflowId prefix), not an
        // app-side filter over a globally-capped stream — otherwise another project contributing
        // newer executions could push this project's out of the scan window and the project-scoped
        // list would silently return an incomplete set. The app-side filter uses the exact
        // ownership predicate (WorkflowExecutionId.belongsToProject: digit-only suffix), NOT a raw
        // prefix match — a raw prefix would leak project `a-b`'s executions into project `a`'s list.
        var prefix = WorkflowExecutionId.implementProjectPrefix(project);
        try (Stream<WorkflowExecutionMetadata> executions = workflowClient.listExecutions(listQuery(prefix))) {
            return executions
                    .filter(md -> WorkflowExecutionId.belongsToProject(
                            md.getExecution().getWorkflowId(), project))
                    .limit(limit)
                    .map(this::toView)
                    .toList();
        }
    }

    @Override
    public Optional<WorkflowExecutionView> describe(String workflowId) {
        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
        try {
            // Enrich the single-execution read model with the bounded gate state, queried from the
            // workflow (GC-Q016). The list path deliberately omits it — querying every execution would
            // be O(n) round-trips.
            return Optional.of(toView(stub.describe(), queryGateState(stub)));
        } catch (WorkflowNotFoundException notFound) {
            return Optional.empty();
        }
    }

    /**
     * Query the workflow's bounded gate state, degrading to {@code null} when it cannot be answered.
     * Querying requires a live worker and a query-capable (open) execution; a closed execution or an
     * absent worker throws. Gate state is optional read-model enrichment, so a failed query must not
     * fail the describe.
     */
    private WorkflowExecutionView.GateState queryGateState(WorkflowStub stub) {
        try {
            return mapGateState(stub.query("gateState", GateState.class));
        } catch (RuntimeException queryFailure) {
            log.debug("Gate-state query unavailable: {}", queryFailure.toString());
            return null;
        }
    }

    @Override
    public void signal(String workflowId, SendSignalCommand command) {
        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
        try {
            switch (command.type()) {
                case CANCEL -> stub.signal("cancel", new CancelSignal(command.reason()));
                case RETRY_FROM -> stub.signal(
                        "retryFrom", new RetryFromSignal(toImplementPhase(command.retryFromPhase()), command.reason()));
                case REVIEW_CAP_DISPOSITION -> stub.signal(
                        "applyReviewCapDisposition",
                        new ReviewCapDispositionSignal(
                                toReviewerKind(command.reviewer()), toCapDisposition(command.disposition())));
                default -> throw new IllegalStateException("Unsupported signal type: " + command.type());
            }
        } catch (WorkflowNotFoundException closed) {
            // Handles the race where the execution closes between the service eligibility check and
            // this signal. Surface the standard not-found envelope instead of an opaque Temporal error.
            throw new NotFoundException("Workflow execution not found: " + workflowId);
        }
    }

    private static String listQuery(String workflowIdPrefix) {
        return "WorkflowType = '" + temporalTypeName(WorkflowType.IMPLEMENT) + "' AND WorkflowId STARTS_WITH '"
                + workflowIdPrefix + "'";
    }

    private ImplementWorkflowInput inputFor(StartWorkflowCommand command) {
        // The completion command is the automation command the worker executes; it is derived from
        // server-side config only and is never caller-supplied, so the control API cannot become an
        // arbitrary-command-execution primitive in the worker environment.
        return new ImplementWorkflowInput(
                command.project(),
                command.issueNumber(),
                properties.defaultCompletionCommand(),
                command.sonarProjectKey(),
                command.reviewCap(),
                command.requirementUids(),
                command.pollIntervalSeconds(),
                toContractRoute(command.route()));
    }

    /**
     * Convert the domain {@link com.keplerops.groundcontrol.domain.llm.ResolvedLlmRoute} resolved by
     * {@code WorkflowExecutionService} into the Temporal contract carrier bound to this execution's
     * durable input (ADR-028). {@code null} when route resolution was unavailable/not applicable.
     */
    private static ResolvedLlmRoute toContractRoute(com.keplerops.groundcontrol.domain.llm.ResolvedLlmRoute route) {
        if (route == null) {
            return null;
        }
        return new ResolvedLlmRoute(
                route.contractVersion(),
                route.project(),
                route.stage(),
                route.tier(),
                route.providerId(),
                route.modelId(),
                route.configDigest());
    }

    private static Map<String, Object> memoFor(StartWorkflowCommand command) {
        Map<String, Object> memo = new LinkedHashMap<>();
        memo.put(MEMO_PROJECT, command.project());
        memo.put(MEMO_ISSUE_NUMBER, command.issueNumber());
        if (!command.requirementUids().isEmpty()) {
            memo.put(MEMO_REQUIREMENT_UIDS, command.requirementUids());
        }
        return memo;
    }

    WorkflowExecutionView toView(WorkflowExecutionMetadata metadata) {
        return toView(metadata, null);
    }

    WorkflowExecutionView toView(WorkflowExecutionMetadata metadata, WorkflowExecutionView.GateState gateState) {
        var execution = metadata.getExecution();
        return new WorkflowExecutionView(
                execution.getWorkflowId(),
                execution.getRunId(),
                // Only IMPLEMENT is startable through this surface, and the Visibility query already
                // filters to ImplementWorkflow, so every listed execution maps to IMPLEMENT.
                WorkflowType.IMPLEMENT,
                mapStatus(metadata.getStatus()),
                metadata.getStartTime(),
                metadata.getCloseTime(),
                metadata.getHistoryLength(),
                new WorkflowExecutionView.Correlation(
                        memoString(metadata, MEMO_PROJECT),
                        memoInteger(metadata, MEMO_ISSUE_NUMBER),
                        memoStringList(metadata, MEMO_REQUIREMENT_UIDS)),
                gateState);
    }

    static WorkflowExecutionView.GateState mapGateState(GateState state) {
        if (state == null) {
            return null;
        }
        return new WorkflowExecutionView.GateState(
                fromImplementPhase(state.phase()),
                toWorkflowOutcome(state.outcome()),
                state.waitingForMerge(),
                fromImplementPhase(state.escalatedPhase()),
                fromReviewerKind(state.escalatedReviewer()));
    }

    static RetryPhase fromImplementPhase(ImplementPhase phase) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case A_PLAN_IMPLEMENT -> RetryPhase.A_PLAN_IMPLEMENT;
            case B_QUALITY_GATE -> RetryPhase.B_QUALITY_GATE;
            case C_STAGE_COMMIT_PUSH -> RetryPhase.C_STAGE_COMMIT_PUSH;
            case D_SHIP_PIPELINE -> RetryPhase.D_SHIP_PIPELINE;
            case E_POST_MERGE_RECONCILE -> RetryPhase.E_POST_MERGE_RECONCILE;
        };
    }

    static WorkflowOutcome toWorkflowOutcome(ImplementOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case READY_FOR_REVIEW -> WorkflowOutcome.READY_FOR_REVIEW;
            case MERGED -> WorkflowOutcome.MERGED;
            case ESCALATED -> WorkflowOutcome.ESCALATED;
            case CANCELLED -> WorkflowOutcome.CANCELLED;
        };
    }

    static Reviewer fromReviewerKind(ReviewerKind reviewer) {
        if (reviewer == null) {
            return null;
        }
        return switch (reviewer) {
            case CODEX -> Reviewer.CODEX;
            case TEST_QUALITY -> Reviewer.TEST_QUALITY;
        };
    }

    private static String temporalTypeName(WorkflowType workflowType) {
        return switch (workflowType) {
            case IMPLEMENT -> "ImplementWorkflow";
        };
    }

    static WorkflowExecutionStatus mapStatus(io.temporal.api.enums.v1.WorkflowExecutionStatus status) {
        if (status == null) {
            return WorkflowExecutionStatus.UNKNOWN;
        }
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_RUNNING -> WorkflowExecutionStatus.RUNNING;
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> WorkflowExecutionStatus.COMPLETED;
            case WORKFLOW_EXECUTION_STATUS_FAILED -> WorkflowExecutionStatus.FAILED;
            case WORKFLOW_EXECUTION_STATUS_CANCELED -> WorkflowExecutionStatus.CANCELED;
            case WORKFLOW_EXECUTION_STATUS_TERMINATED -> WorkflowExecutionStatus.TERMINATED;
            case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> WorkflowExecutionStatus.CONTINUED_AS_NEW;
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> WorkflowExecutionStatus.TIMED_OUT;
            case WORKFLOW_EXECUTION_STATUS_PAUSED -> WorkflowExecutionStatus.PAUSED;
            default -> WorkflowExecutionStatus.UNKNOWN;
        };
    }

    static ImplementPhase toImplementPhase(RetryPhase phase) {
        return switch (phase) {
            case A_PLAN_IMPLEMENT -> ImplementPhase.A_PLAN_IMPLEMENT;
            case B_QUALITY_GATE -> ImplementPhase.B_QUALITY_GATE;
            case C_STAGE_COMMIT_PUSH -> ImplementPhase.C_STAGE_COMMIT_PUSH;
            case D_SHIP_PIPELINE -> ImplementPhase.D_SHIP_PIPELINE;
            case E_POST_MERGE_RECONCILE -> ImplementPhase.E_POST_MERGE_RECONCILE;
        };
    }

    static ReviewerKind toReviewerKind(Reviewer reviewer) {
        return switch (reviewer) {
            case CODEX -> ReviewerKind.CODEX;
            case TEST_QUALITY -> ReviewerKind.TEST_QUALITY;
        };
    }

    static CapDisposition toCapDisposition(SignalDisposition disposition) {
        return switch (disposition) {
            case PROCEED -> CapDisposition.PROCEED;
            case ONE_MORE_CYCLE -> CapDisposition.ONE_MORE_CYCLE;
            case ESCALATE_TO_HUMAN -> CapDisposition.ESCALATE_TO_HUMAN;
        };
    }

    private static String memoString(WorkflowExecutionMetadata metadata, String key) {
        try {
            Object value = metadata.getMemo(key, String.class);
            return value == null ? null : value.toString();
        } catch (RuntimeException decodeFailure) {
            log.debug(MEMO_DECODE_FAILURE_LOG, key, decodeFailure);
            return null;
        }
    }

    private static Integer memoInteger(WorkflowExecutionMetadata metadata, String key) {
        try {
            Object value = metadata.getMemo(key, Integer.class);
            if (value instanceof Number number) {
                return number.intValue();
            }
            return null;
        } catch (RuntimeException decodeFailure) {
            log.debug(MEMO_DECODE_FAILURE_LOG, key, decodeFailure);
            return null;
        }
    }

    private static List<String> memoStringList(WorkflowExecutionMetadata metadata, String key) {
        try {
            Object value = metadata.getMemo(key, List.class);
            if (value instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        } catch (RuntimeException decodeFailure) {
            log.debug(MEMO_DECODE_FAILURE_LOG, key, decodeFailure);
            return List.of();
        }
    }
}
