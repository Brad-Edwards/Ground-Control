package com.keplerops.groundcontrol.unit.domain.mcptelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.mcptelemetry.McpToolEvent;
import com.keplerops.groundcontrol.domain.mcptelemetry.repository.McpToolEventRepository;
import com.keplerops.groundcontrol.domain.mcptelemetry.repository.McpToolEventRepository.ToolAggregateRow;
import com.keplerops.groundcontrol.domain.mcptelemetry.service.McpTelemetryService;
import com.keplerops.groundcontrol.domain.mcptelemetry.service.RecordMcpToolEventCommand;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpTelemetryServiceTest {

    @Mock
    private McpToolEventRepository repository;

    @InjectMocks
    private McpTelemetryService service;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");

    // ------------------------------------------------------------------
    // aggregate — grouping and errorRate
    // ------------------------------------------------------------------

    @Test
    void aggregateMapsRowsAndComputesErrorRate() {
        // The database returns already-aggregated rows; the service maps them and derives
        // the error rate. gc_query: 3 calls, 1 error; gc_finding: 1 call, 0 errors.
        when(repository.aggregateByEventTsBetween(any(), any()))
                .thenReturn(List.of(
                        aggregateRow("gc_query", 3L, 1L, 100L, 200L, 200L),
                        aggregateRow("gc_finding", 1L, 0L, 10L, 10L, 10L)));

        var result = service.aggregate(FROM, TO);

        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);
        assertThat(result.tools()).hasSize(2);

        var gcQuery = result.tools().stream()
                .filter(r -> "gc_query".equals(r.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(gcQuery.count()).isEqualTo(3);
        assertThat(gcQuery.errorRate()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(gcQuery.p50Ms()).isEqualTo(100L);
        assertThat(gcQuery.p95Ms()).isEqualTo(200L);
        assertThat(gcQuery.p99Ms()).isEqualTo(200L);

        var gcFinding = result.tools().stream()
                .filter(r -> "gc_finding".equals(r.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(gcFinding.count()).isEqualTo(1);
        assertThat(gcFinding.errorRate()).isZero();
    }

    @Test
    void aggregateReturnsEmptyListWhenNoEvents() {
        when(repository.aggregateByEventTsBetween(any(), any())).thenReturn(List.of());

        var result = service.aggregate(FROM, TO);

        assertThat(result.tools()).isEmpty();
    }

    // ------------------------------------------------------------------
    // aggregate — window validation
    // ------------------------------------------------------------------

    @Test
    void aggregateRejectsNullFrom() {
        assertThatThrownBy(() -> service.aggregate(null, TO))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("from");
    }

    @Test
    void aggregateRejectsNullTo() {
        assertThatThrownBy(() -> service.aggregate(FROM, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("to");
    }

    @Test
    void aggregateRejectsFromAfterTo() {
        assertThatThrownBy(() -> service.aggregate(TO, FROM))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("before");
    }

    @Test
    void aggregateRejectsWindowExceedingMaxDays() {
        var longFrom = Instant.parse("2026-01-01T00:00:00Z");
        var longTo = longFrom.plusSeconds(32L * 24 * 3600); // 32 days
        assertThatThrownBy(() -> service.aggregate(longFrom, longTo))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("31 days");
    }

    // ------------------------------------------------------------------
    // record
    // ------------------------------------------------------------------

    @Test
    void recordMapsAllCommandFieldsOntoTheSavedEvent() {
        var command = new RecordMcpToolEventCommand("gc_query", "list", "ok", 42L, "ground-control", FROM);

        service.recordEvent(command);

        // Capture the persisted entity and assert every field is mapped from the command,
        // so a swapped accessor, a wrong duration source, or a dropped project would fail.
        var captor = ArgumentCaptor.forClass(McpToolEvent.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        McpToolEvent saved = captor.getValue();
        assertThat(saved.getTool()).isEqualTo("gc_query");
        assertThat(saved.getAction()).isEqualTo("list");
        assertThat(saved.getOutcome()).isEqualTo("ok");
        assertThat(saved.getDurationMs()).isEqualTo(42L);
        assertThat(saved.getProject()).isEqualTo("ground-control");
        assertThat(saved.getEventTs()).isEqualTo(FROM);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ToolAggregateRow aggregateRow(
            String tool, long count, long errorCount, long p50Ms, long p95Ms, long p99Ms) {
        return new ToolAggregateRow() {
            @Override
            public String getTool() {
                return tool;
            }

            @Override
            public long getCount() {
                return count;
            }

            @Override
            public long getErrorCount() {
                return errorCount;
            }

            @Override
            public long getP50Ms() {
                return p50Ms;
            }

            @Override
            public long getP95Ms() {
                return p95Ms;
            }

            @Override
            public long getP99Ms() {
                return p99Ms;
            }
        };
    }
}
