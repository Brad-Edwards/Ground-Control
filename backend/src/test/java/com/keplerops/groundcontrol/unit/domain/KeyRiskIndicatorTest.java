package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.KeyRiskIndicator;
import com.keplerops.groundcontrol.domain.riskscenarios.state.KriThresholdBand;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KeyRiskIndicatorTest {

    private KeyRiskIndicator kri(BigDecimal yellow, BigDecimal red, String direction) {
        var kri = new KeyRiskIndicator(new Project("p", "P"), "KRI-001", "Test");
        kri.setYellowThreshold(yellow);
        kri.setRedThreshold(red);
        if (direction != null) {
            kri.setDirection(direction);
        }
        return kri;
    }

    @Test
    void higherIsWorseGreenWhenBelowYellow() {
        assertThat(kri(new BigDecimal("10"), new BigDecimal("20"), null).classify(new BigDecimal("5")))
                .isEqualTo(KriThresholdBand.GREEN);
    }

    @Test
    void higherIsWorseYellowAtYellowThreshold() {
        assertThat(kri(new BigDecimal("10"), new BigDecimal("20"), null).classify(new BigDecimal("10")))
                .isEqualTo(KriThresholdBand.YELLOW);
    }

    @Test
    void higherIsWorseRedAtRedThreshold() {
        assertThat(kri(new BigDecimal("10"), new BigDecimal("20"), null).classify(new BigDecimal("20")))
                .isEqualTo(KriThresholdBand.RED);
    }

    // Boundary coverage: a value one tick below the yellow threshold must
    // still classify GREEN for HIGHER_IS_WORSE. A regression flipping the
    // GREEN/YELLOW comparison from `<` to `<=` (or vice versa) would silently
    // change the band of values sitting just under the breakpoint.
    @Test
    void higherIsWorseGreenJustBelowYellow() {
        assertThat(kri(new BigDecimal("10"), new BigDecimal("20"), null).classify(new BigDecimal("9")))
                .isEqualTo(KriThresholdBand.GREEN);
    }

    @Test
    void lowerIsWorseGreenWhenAboveYellow() {
        assertThat(kri(new BigDecimal("80"), new BigDecimal("60"), "LOWER_IS_WORSE")
                        .classify(new BigDecimal("90")))
                .isEqualTo(KriThresholdBand.GREEN);
    }

    @Test
    void lowerIsWorseRedWhenAtRed() {
        assertThat(kri(new BigDecimal("80"), new BigDecimal("60"), "LOWER_IS_WORSE")
                        .classify(new BigDecimal("60")))
                .isEqualTo(KriThresholdBand.RED);
    }

    // LOWER_IS_WORSE boundary coverage. The original suite did not test the
    // YELLOW band for the inverted direction; flipping `<=` to `<` on the
    // yellow comparison would silently break the boundary case.
    @Test
    void lowerIsWorseYellowAtYellowThreshold() {
        assertThat(kri(new BigDecimal("80"), new BigDecimal("60"), "LOWER_IS_WORSE")
                        .classify(new BigDecimal("80")))
                .isEqualTo(KriThresholdBand.YELLOW);
    }

    @Test
    void lowerIsWorseYellowBetweenYellowAndRed() {
        assertThat(kri(new BigDecimal("80"), new BigDecimal("60"), "LOWER_IS_WORSE")
                        .classify(new BigDecimal("70")))
                .isEqualTo(KriThresholdBand.YELLOW);
    }

    @Test
    void classifyReturnsNullWhenThresholdsUnset() {
        var k = new KeyRiskIndicator(new Project("p", "P"), "KRI-001", "Test");
        assertThat(k.classify(BigDecimal.TEN)).isNull();
    }

    // Null-value guard: the guard in classify() returns null when value is null.
    // Removing that guard would NPE on any caller that passes null without
    // triggering a test failure under the original suite.
    @Test
    void classifyReturnsNullWhenValueIsNull() {
        var k = kri(new BigDecimal("10"), new BigDecimal("20"), null);
        assertThat(k.classify(null)).isNull();
    }

    @Test
    void recordMeasurementThrowsWhenUnconfigured() {
        var k = new KeyRiskIndicator(new Project("p", "P"), "KRI-001", "Test");
        var now = Instant.parse("2026-04-04T12:00:00Z");
        assertThatThrownBy(() -> k.recordMeasurement(BigDecimal.TEN, now))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void recordMeasurementUpdatesState() {
        var k = kri(new BigDecimal("10"), new BigDecimal("20"), null);
        var now = Instant.parse("2026-04-04T12:00:00Z");
        var band = k.recordMeasurement(new BigDecimal("25"), now);
        assertThat(band).isEqualTo(KriThresholdBand.RED);
        assertThat(k.getCurrentBand()).isEqualTo(KriThresholdBand.RED);
        assertThat(k.getCurrentValue()).isEqualByComparingTo(new BigDecimal("25"));
        assertThat(k.getLastMeasuredAt()).isEqualTo(now);
    }
}
