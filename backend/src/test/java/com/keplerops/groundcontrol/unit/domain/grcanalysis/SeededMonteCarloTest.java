package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.grcanalysis.util.SeededMonteCarlo;
import org.junit.jupiter.api.Test;

class SeededMonteCarloTest {

    @Test
    void sameSeedProducesIdenticalSequences() {
        var a = new SeededMonteCarlo(42L);
        var b = new SeededMonteCarlo(42L);

        double[] sa = a.sample(100, a::nextUniform);
        double[] sb = b.sample(100, b::nextUniform);

        assertThat(sa).containsExactly(sb);
    }

    @Test
    void differentSeedsProduceDifferentSequences() {
        var a = new SeededMonteCarlo(1L);
        var b = new SeededMonteCarlo(2L);

        double[] sa = a.sample(50, a::nextUniform);
        double[] sb = b.sample(50, b::nextUniform);

        assertThat(sa).isNotEqualTo(sb);
    }

    @Test
    void triangularSamplesWithinBounds() {
        var rng = new SeededMonteCarlo(7L);
        double[] s = rng.sample(1000, () -> rng.nextTriangular(1.0, 3.0, 5.0));

        for (double v : s) {
            assertThat(v).isBetween(1.0, 5.0);
        }
    }

    @Test
    void uniformRangeSamplesWithinBounds() {
        var rng = new SeededMonteCarlo(7L);
        double[] s = rng.sample(1000, () -> rng.nextUniformRange(2.0, 8.0));

        for (double v : s) {
            assertThat(v).isBetween(2.0, 8.0);
        }
    }

    @Test
    void triangularRejectsDegenerateParameters() {
        var rng = new SeededMonteCarlo(0L);
        assertThatThrownBy(() -> rng.nextTriangular(5.0, 3.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min <= mode <= max");
        assertThatThrownBy(() -> rng.nextTriangular(1.0, 1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min < max");
        assertThatThrownBy(() -> rng.nextTriangular(Double.NaN, 1.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    void uniformRangeRejectsInvertedBounds() {
        var rng = new SeededMonteCarlo(0L);
        assertThatThrownBy(() -> rng.nextUniformRange(5.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min must be less than max");
    }

    @Test
    void sampleRejectsNonPositiveIterations() {
        var rng = new SeededMonteCarlo(0L);
        assertThatThrownBy(() -> rng.sample(0, rng::nextUniform))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iterations must be positive");
    }

    @Test
    void sampleRejectsNullSupplier() {
        var rng = new SeededMonteCarlo(0L);
        assertThatThrownBy(() -> rng.sample(10, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
