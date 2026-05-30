package com.keplerops.groundcontrol.unit.domain.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.model.DistributionKind;
import com.keplerops.groundcontrol.domain.backlog.model.WsjfDistribution;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WsjfDistributionTest {

    @Test
    void seededComputationIsReproducible() {
        var ubv = CostOfDelayComponent.triangular(2, 5, 8, "alice");
        var tc = CostOfDelayComponent.triangular(1, 3, 7, "alice");
        var rroe = CostOfDelayComponent.point(2, "alice");
        var jd = CostOfDelayComponent.triangular(1, 2, 4, "alice");

        var a = WsjfDistribution.compute(ubv, tc, rroe, jd, 12345L, 5000);
        var b = WsjfDistribution.compute(ubv, tc, rroe, jd, 12345L, 5000);

        assertThat(a.samples()).containsExactly(b.samples());
        assertThat(a.mean()).isEqualTo(b.mean());
        assertThat(a.median()).isEqualTo(b.median());
        assertThat(a.p10()).isEqualTo(b.p10());
        assertThat(a.p90()).isEqualTo(b.p90());
    }

    @Test
    void distributionStatisticsAreOrdered() {
        var ubv = CostOfDelayComponent.triangular(1, 4, 7, "alice");
        var tc = CostOfDelayComponent.triangular(1, 4, 7, "alice");
        var rroe = CostOfDelayComponent.triangular(1, 4, 7, "alice");
        var jd = CostOfDelayComponent.triangular(1, 2, 3, "alice");

        var dist = WsjfDistribution.compute(ubv, tc, rroe, jd, 1L, 10_000);

        assertThat(dist.p10()).isLessThanOrEqualTo(dist.median());
        assertThat(dist.median()).isLessThanOrEqualTo(dist.p90());
        assertThat(dist.mean()).isPositive();
    }

    @Test
    void pointDistributionCollapsesToDeterministicSamples() {
        var v = CostOfDelayComponent.point(5, "alice");
        var jd = CostOfDelayComponent.point(2, "alice");
        var dist = WsjfDistribution.compute(v, v, v, jd, 0L, 100);

        for (double s : dist.samples()) {
            assertThat(s).isCloseTo(7.5, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void zeroIterationsRejected() {
        var v = CostOfDelayComponent.point(5, "alice");
        var jd = CostOfDelayComponent.point(2, "alice");
        assertThatThrownBy(() -> WsjfDistribution.compute(v, v, v, jd, 0L, 0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void nullComponentsRejected() {
        var v = CostOfDelayComponent.point(5, "alice");
        var jd = CostOfDelayComponent.point(2, "alice");
        assertThatThrownBy(() -> WsjfDistribution.compute(null, v, v, jd, 0L, 10))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void costOfDelayComponentRejectsInvalidDistribution() {
        assertThatThrownBy(() -> CostOfDelayComponent.triangular(5, 3, 1, "x"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CostOfDelayComponent.uniform(5, 5, "x")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new CostOfDelayComponent(DistributionKind.POINT, 1, 2, 3, "x"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new CostOfDelayComponent(DistributionKind.TRIANGULAR, -1, 0, 1, "x"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void probabilityFirstDominatesSecondReportsTrueOrdering() {
        var clearWinUbv = CostOfDelayComponent.triangular(10, 11, 12, "x");
        var clearLossUbv = CostOfDelayComponent.triangular(1, 1.5, 2, "x");
        var commonRest = CostOfDelayComponent.point(1, "x");
        var commonJd = CostOfDelayComponent.point(1, "x");

        var winner = WsjfDistribution.compute(clearWinUbv, commonRest, commonRest, commonJd, 7L, 500);
        var loser = WsjfDistribution.compute(clearLossUbv, commonRest, commonRest, commonJd, 7L, 500);

        assertThat(WsjfDistribution.probabilityFirstDominatesSecond(winner, loser))
                .isEqualTo(1.0);
        assertThat(WsjfDistribution.probabilityFirstDominatesSecond(loser, winner))
                .isEqualTo(0.0);
    }

    @Test
    void rankingDeltaEmitsMoves() {
        var hi = CostOfDelayComponent.triangular(10, 12, 14, "x");
        var lo = CostOfDelayComponent.triangular(1, 2, 3, "x");
        var unit = CostOfDelayComponent.point(1, "x");

        var distHi = WsjfDistribution.compute(hi, unit, unit, unit, 1L, 200);
        var distLo = WsjfDistribution.compute(lo, unit, unit, unit, 1L, 200);

        var aId = UUID.randomUUID();
        var bId = UUID.randomUUID();

        // Before: A is high, B is low.
        var deltas = WsjfDistribution.rankingDelta(
                List.of(aId, bId), List.of(distHi, distLo),
                List.of(aId, bId), List.of(distLo, distHi));

        // A demoted by 1, B promoted by 1.
        var byId = deltas.stream().collect(java.util.stream.Collectors.toMap(d -> d.id(), d -> d));
        assertThat(byId.get(aId).beforeRank()).isEqualTo(1);
        assertThat(byId.get(aId).afterRank()).isEqualTo(2);
        assertThat(byId.get(aId).delta()).isEqualTo(-1);
        assertThat(byId.get(bId).beforeRank()).isEqualTo(2);
        assertThat(byId.get(bId).afterRank()).isEqualTo(1);
        assertThat(byId.get(bId).delta()).isEqualTo(1);
    }
}
