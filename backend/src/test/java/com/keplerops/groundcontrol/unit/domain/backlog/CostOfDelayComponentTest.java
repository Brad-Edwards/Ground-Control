package com.keplerops.groundcontrol.unit.domain.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.model.DistributionKind;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.grcanalysis.util.SeededMonteCarlo;
import org.junit.jupiter.api.Test;

class CostOfDelayComponentTest {

    // ── withAttributedTo ────────────────────────────────────────────────────

    @Test
    void withAttributedToReturnsCopyWithNewActor() {
        var original = CostOfDelayComponent.triangular(1, 3, 5, "alice");
        var stamped = original.withAttributedTo("bob");

        assertThat(stamped.attributedTo()).isEqualTo("bob");
        // All distribution parameters preserved.
        assertThat(stamped.kind()).isEqualTo(DistributionKind.TRIANGULAR);
        assertThat(stamped.min()).isEqualTo(original.min());
        assertThat(stamped.mode()).isEqualTo(original.mode());
        assertThat(stamped.max()).isEqualTo(original.max());
        // Original not mutated.
        assertThat(original.attributedTo()).isEqualTo("alice");
    }

    @Test
    void withAttributedToAcceptsNullActor() {
        var original = CostOfDelayComponent.point(5, "alice");
        var stamped = original.withAttributedTo(null);
        assertThat(stamped.attributedTo()).isNull();
    }

    // ── draw() ──────────────────────────────────────────────────────────────

    @Test
    void pointDistributionDrawAlwaysReturnsMin() {
        var component = CostOfDelayComponent.point(7.0, "alice");
        var rng = new SeededMonteCarlo(42L);

        for (int i = 0; i < 20; i++) {
            assertThat(component.draw(rng)).isEqualTo(7.0);
        }
    }

    @Test
    void uniformDistributionDrawStaysBetweenMinAndMax() {
        var component = CostOfDelayComponent.uniform(2.0, 8.0, "alice");
        var rng = new SeededMonteCarlo(99L);

        for (int i = 0; i < 100; i++) {
            double sample = component.draw(rng);
            assertThat(sample).isBetween(2.0, 8.0);
        }
    }

    @Test
    void triangularDistributionDrawStaysBetweenMinAndMax() {
        var component = CostOfDelayComponent.triangular(1.0, 4.0, 9.0, "alice");
        var rng = new SeededMonteCarlo(7L);

        for (int i = 0; i < 100; i++) {
            double sample = component.draw(rng);
            assertThat(sample).isBetween(1.0, 9.0);
        }
    }

    @Test
    void triangularDrawIsReproducibleAcrossIdenticalSeeds() {
        var component = CostOfDelayComponent.triangular(1.0, 3.0, 5.0, "alice");

        var rng1 = new SeededMonteCarlo(12L);
        var rng2 = new SeededMonteCarlo(12L);

        assertThat(component.draw(rng1)).isEqualTo(component.draw(rng2));
    }

    // ── NaN validation ──────────────────────────────────────────────────────

    @Test
    void nanMinRejected() {
        assertThatThrownBy(() -> new CostOfDelayComponent(DistributionKind.POINT, Double.NaN, 1.0, 1.0, "x"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    void nanModeRejected() {
        assertThatThrownBy(() -> new CostOfDelayComponent(DistributionKind.TRIANGULAR, 1.0, Double.NaN, 5.0, "x"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    void nanMaxRejected() {
        assertThatThrownBy(() -> new CostOfDelayComponent(DistributionKind.POINT, 1.0, 1.0, Double.NaN, "x"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    void nullKindRejected() {
        assertThatThrownBy(() -> new CostOfDelayComponent(null, 1.0, 1.0, 1.0, "x"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("kind");
    }

    // ── factory method convenience ───────────────────────────────────────────

    @Test
    void uniformFactorySetsModeToMin() {
        var c = CostOfDelayComponent.uniform(3.0, 9.0, "alice");
        assertThat(c.kind()).isEqualTo(DistributionKind.UNIFORM);
        assertThat(c.min()).isEqualTo(3.0);
        assertThat(c.mode()).isEqualTo(3.0);
        assertThat(c.max()).isEqualTo(9.0);
    }

    @Test
    void pointFactorySetsModeAndMaxToValue() {
        var c = CostOfDelayComponent.point(5.0, "alice");
        assertThat(c.kind()).isEqualTo(DistributionKind.POINT);
        assertThat(c.min()).isEqualTo(5.0);
        assertThat(c.mode()).isEqualTo(5.0);
        assertThat(c.max()).isEqualTo(5.0);
    }
}
