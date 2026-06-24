package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository.RunRollupRow;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.ImportRunCostCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowRunFilter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class WorkflowTelemetryServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowPhaseEventRepository phaseEventRepository;

    @InjectMocks
    private WorkflowTelemetryService service;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");

    // ---- recordRun: create vs merge upsert -----------------------------------------------------

    @Test
    void recordRunCreatesNewRunWhenNoneMatchesTheUpsertKey() {
        // The new-run path inserts via saveAndFlush so a unique-key violation surfaces eagerly.
        when(runRepository.findRunForUpsert(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var command = runCommand()
                .withState(WorkflowRunState.READY_FOR_REVIEW)
                .withOutcome(WorkflowRunOutcome.MERGED)
                .build();

        var saved = service.recordRun(command);

        var captor = ArgumentCaptor.forClass(WorkflowRun.class);
        org.mockito.Mockito.verify(runRepository).saveAndFlush(captor.capture());
        WorkflowRun run = captor.getValue();
        assertThat(run.getProject()).isEqualTo("ground-control");
        assertThat(run.getIssueNumber()).isEqualTo(859);
        assertThat(run.getBranch()).isEqualTo("859-feature");
        assertThat(run.getWorkflowType()).isEqualTo("implement");
        assertThat(run.getFinalState()).isEqualTo(WorkflowRunState.READY_FOR_REVIEW);
        assertThat(run.getOutcome()).isEqualTo(WorkflowRunOutcome.MERGED);
        assertThat(run.getProvenance()).isEqualTo(TelemetryProvenance.ISSUE_THREAD);
        assertThat(saved).isSameAs(run);
    }

    @Test
    void recordRunRaisesConflictWhenConcurrentInsertViolatesTheUniqueKey() {
        // Two concurrent observations of the same key: the loser's insert is rejected by the unique
        // index, surfaced as a retryable ConflictException (a retry then takes the update path).
        when(runRepository.findRunForUpsert(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.recordRun(runCommand().build()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("retry");
    }

    @Test
    void recordRunMergesNonNullFieldsOntoExistingRun() {
        // An existing RUNNING run is refined by a later observation carrying only the merge outcome.
        var existing = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        existing.setIssueNumber(859);
        existing.setBranch("859-feature");
        existing.setStartedAt(FROM);
        when(runRepository.findRunForUpsert(any(), any(), any(), any())).thenReturn(Optional.of(existing));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var update = new RecordWorkflowRunCommand(
                "ground-control",
                null,
                859,
                42,
                "859-feature",
                "implement",
                null,
                null,
                null,
                TO,
                WorkflowRunState.MERGED,
                WorkflowRunOutcome.MERGED,
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        service.recordRun(update);

        // The early-start timestamp survives; the merge outcome and end time are applied.
        assertThat(existing.getStartedAt()).isEqualTo(FROM);
        assertThat(existing.getEndedAt()).isEqualTo(TO);
        assertThat(existing.getPrNumber()).isEqualTo(42);
        assertThat(existing.getFinalState()).isEqualTo(WorkflowRunState.MERGED);
        assertThat(existing.getOutcome()).isEqualTo(WorkflowRunOutcome.MERGED);
    }

    @Test
    void recordRunRejectsBlankProject() {
        assertThatThrownBy(
                        () -> service.recordRun(runCommand().withProject("  ").build()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("project");
    }

    @Test
    void recordRunRejectsNullProvenance() {
        assertThatThrownBy(() ->
                        service.recordRun(runCommand().withProvenance(null).build()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("provenance");
    }

    @Test
    void recordRunRejectsReservedMarkerInBranch() {
        assertThatThrownBy(() -> service.recordRun(
                        runCommand().withBranch("x<!-- gc:phase -->").build()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void recordRunRejectsNegativeCostProxy() {
        assertThatThrownBy(() -> service.recordRun(
                        runCommand().withCost(new BigDecimal("-1.00")).build()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("costProxy");
    }

    // ---- recordPhaseEvent ----------------------------------------------------------------------

    @Test
    void recordPhaseEventThrowsNotFoundWhenRunMissingOrForeignProject() {
        // A foreign-project run resolves to empty via findByIdAndProject, so a cross-project caller
        // is treated the same as not-found and cannot append events to another project's run.
        var runId = UUID.randomUUID();
        when(runRepository.findByIdAndProject(runId, "gc")).thenReturn(Optional.empty());
        var command = new RecordPhaseEventCommand(
                runId, "gc", "ci", PhaseEventType.FAILED, 1, FROM, 1000L, "failure", TelemetryProvenance.ISSUE_THREAD);
        assertThatThrownBy(() -> service.recordPhaseEvent(command)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void recordPhaseEventDenormalizesRunProjectOntoEvent() {
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        when(runRepository.findByIdAndProject(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordPhaseEvent(new RecordPhaseEventCommand(
                runId,
                "ground-control",
                "codex_review",
                PhaseEventType.COMPLETED,
                2,
                FROM,
                5000L,
                "clean",
                TelemetryProvenance.ISSUE_THREAD));

        var captor = ArgumentCaptor.forClass(WorkflowPhaseEvent.class);
        org.mockito.Mockito.verify(phaseEventRepository).save(captor.capture());
        WorkflowPhaseEvent event = captor.getValue();
        assertThat(event.getProject()).isEqualTo("ground-control");
        assertThat(event.getPhase()).isEqualTo("codex_review");
        assertThat(event.getCycleIndex()).isEqualTo(2);
        assertThat(event.getEventType()).isEqualTo(PhaseEventType.COMPLETED);
    }

    @Test
    void recordPhaseEventRejectsReservedMarkerInPhase() {
        var command = new RecordPhaseEventCommand(
                UUID.randomUUID(),
                "gc",
                "<!-- gc:phase -->",
                PhaseEventType.STARTED,
                null,
                FROM,
                null,
                null,
                TelemetryProvenance.ISSUE_THREAD);
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("reserved");
    }

    // ---- importCost ----------------------------------------------------------------------------

    @Test
    void importCostAppliesOnlyNonNullFields() {
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        run.setProvider("anthropic");
        when(runRepository.findByIdAndProject(runId, "ground-control")).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importCost(new ImportRunCostCommand(
                runId, "ground-control", null, "claude-opus-4-8", null, null, new BigDecimal("12.5000"), "USD", 1000L));

        assertThat(run.getProvider()).isEqualTo("anthropic"); // untouched (null in command)
        assertThat(run.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(run.getCostProxy()).isEqualByComparingTo("12.5000");
        assertThat(run.getCostCurrency()).isEqualTo("USD");
        assertThat(run.getTokenUsage()).isEqualTo(1000L);
    }

    @Test
    void importCostThrowsNotFoundWhenRunMissingOrForeignProject() {
        var runId = UUID.randomUUID();
        when(runRepository.findByIdAndProject(runId, "gc")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.importCost(
                        new ImportRunCostCommand(runId, "gc", null, null, null, null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- aggregate: window validation + mapping ------------------------------------------------

    @Test
    void aggregateRejectsFromAfterTo() {
        assertThatThrownBy(() -> service.aggregate(new WorkflowRunFilter(TO, FROM, "p", null, null, null, null, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("before");
    }

    @Test
    void aggregateRejectsWindowExceedingMaxDays() {
        var longTo = FROM.plusSeconds(400L * 24 * 3600); // 400 days > 366
        assertThatThrownBy(
                        () -> service.aggregate(new WorkflowRunFilter(FROM, longTo, "p", null, null, null, null, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("366");
    }

    @Test
    void aggregateMapsRollupAndDerivesCostPerOutcome() {
        when(runRepository.aggregateRuns(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(rollup(10, 4, 2, 3, new BigDecimal("100.0000"), new BigDecimal("40.0000")));
        when(phaseEventRepository.aggregatePhaseHotspots(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(hotspot("ci", 5, 2, 0, 1000L, 2000L, 3)));

        var result = service.aggregate(new WorkflowRunFilter(FROM, TO, "ground-control", null, null, null, null, null));

        assertThat(result.totalRuns()).isEqualTo(10);
        assertThat(result.mergedRuns()).isEqualTo(4);
        assertThat(result.activeRuns()).isEqualTo(3);
        // 40.0000 merged cost over 4 merged runs => 10.0000 per merged run.
        assertThat(result.costProxyPerMergedRun()).isEqualByComparingTo("10.0000");
        assertThat(result.phaseHotspots()).hasSize(1);
        assertThat(result.phaseHotspots().get(0).phase()).isEqualTo("ci");
        assertThat(result.phaseHotspots().get(0).failedCount()).isEqualTo(2);
        assertThat(result.phaseHotspots().get(0).maxCycleIndex()).isEqualTo(3);
    }

    @Test
    void aggregateCostPerMergedRunIsNullWhenNoMergedRuns() {
        when(runRepository.aggregateRuns(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(rollup(2, 0, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO));
        when(phaseEventRepository.aggregatePhaseHotspots(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        var result = service.aggregate(new WorkflowRunFilter(FROM, TO, "p", null, null, null, null, null));

        assertThat(result.costProxyPerMergedRun()).isNull();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static RunCommandBuilder runCommand() {
        return new RunCommandBuilder();
    }

    /** Small fluent builder so each validation test varies exactly one field. */
    private static final class RunCommandBuilder {
        private String project = "ground-control";
        private String branch = "859-feature";
        private WorkflowRunState state = WorkflowRunState.RUNNING;
        private WorkflowRunOutcome outcome = WorkflowRunOutcome.NONE;
        private TelemetryProvenance provenance = TelemetryProvenance.ISSUE_THREAD;
        private BigDecimal cost = null;

        RunCommandBuilder withProject(String p) {
            this.project = p;
            return this;
        }

        RunCommandBuilder withBranch(String b) {
            this.branch = b;
            return this;
        }

        RunCommandBuilder withState(WorkflowRunState s) {
            this.state = s;
            return this;
        }

        RunCommandBuilder withOutcome(WorkflowRunOutcome o) {
            this.outcome = o;
            return this;
        }

        RunCommandBuilder withProvenance(TelemetryProvenance p) {
            this.provenance = p;
            return this;
        }

        RunCommandBuilder withCost(BigDecimal c) {
            this.cost = c;
            return this;
        }

        RecordWorkflowRunCommand build() {
            return new RecordWorkflowRunCommand(
                    project,
                    null,
                    859,
                    null,
                    branch,
                    "implement",
                    "claude-code",
                    Set.of("GC-O009"),
                    FROM,
                    null,
                    state,
                    outcome,
                    provenance,
                    null,
                    null,
                    null,
                    null,
                    cost,
                    null,
                    null);
        }
    }

    private static RunRollupRow rollup(
            long total, long merged, long closed, long active, BigDecimal totalCost, BigDecimal mergedCost) {
        return new RunRollupRow() {
            @Override
            public long getTotalRuns() {
                return total;
            }

            @Override
            public long getMergedRuns() {
                return merged;
            }

            @Override
            public long getClosedRuns() {
                return closed;
            }

            @Override
            public long getActiveRuns() {
                return active;
            }

            @Override
            public long getEscalatedRuns() {
                return 0;
            }

            @Override
            public long getAbandonedRuns() {
                return 0;
            }

            @Override
            public long getSupersededRuns() {
                return 0;
            }

            @Override
            public Double getCycleTimeP50Min() {
                return 12.0;
            }

            @Override
            public Double getCycleTimeP95Min() {
                return 30.0;
            }

            @Override
            public Double getCycleTimeP99Min() {
                return 45.0;
            }

            @Override
            public BigDecimal getTotalCostProxy() {
                return totalCost;
            }

            @Override
            public BigDecimal getMergedCostProxy() {
                return mergedCost;
            }

            @Override
            public BigDecimal getClosedCostProxy() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getTotalModelInvocations() {
                return 0;
            }

            @Override
            public long getTotalWallClockMinutes() {
                return 0;
            }

            @Override
            public long getTotalTokenUsage() {
                return 0;
            }
        };
    }

    private static WorkflowPhaseEventRepository.PhaseHotspotRow hotspot(
            String phase, long count, long failed, long escalated, Long p50, Long p95, Integer maxCycle) {
        return new WorkflowPhaseEventRepository.PhaseHotspotRow() {
            @Override
            public String getPhase() {
                return phase;
            }

            @Override
            public long getEventCount() {
                return count;
            }

            @Override
            public long getFailedCount() {
                return failed;
            }

            @Override
            public long getEscalatedCount() {
                return escalated;
            }

            @Override
            public Long getP50Ms() {
                return p50;
            }

            @Override
            public Long getP95Ms() {
                return p95;
            }

            @Override
            public Integer getMaxCycleIndex() {
                return maxCycle;
            }
        };
    }
}
