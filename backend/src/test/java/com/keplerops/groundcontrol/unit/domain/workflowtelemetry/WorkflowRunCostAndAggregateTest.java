package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.FROM;
import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository.RunRollupRow;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.ImportRunCostCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowRunFilter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class WorkflowRunCostAndAggregateTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Mock
    private WorkflowMeasurementService measurementService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WorkflowTelemetryService service;

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
        var command = new ImportRunCostCommand(runId, "gc", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.importCost(command)).isInstanceOf(NotFoundException.class);
    }

    // ---- aggregate: window validation + mapping ------------------------------------------------

    @Test
    void aggregateRejectsFromAfterTo() {
        var filter = new WorkflowRunFilter(TO, FROM, "p", null, null, null, null, null);
        assertThatThrownBy(() -> service.aggregate(filter))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("before");
    }

    @Test
    void aggregateRejectsWindowExceedingMaxDays() {
        var longTo = FROM.plusSeconds(400L * 24 * 3600); // 400 days > 366
        var filter = new WorkflowRunFilter(FROM, longTo, "p", null, null, null, null, null);
        assertThatThrownBy(() -> service.aggregate(filter))
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

    /** A run already open on the canonical (project, issue, branch) identity, started at {@code at}. */

    /** A live-emission observation of the same run identity, varying only the timestamps and state. */
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
