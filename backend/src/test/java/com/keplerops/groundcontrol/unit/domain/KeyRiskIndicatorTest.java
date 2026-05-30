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

    @Test
    void classifyReturnsNullWhenThresholdsUnset() {
        var k = new KeyRiskIndicator(new Project("p", "P"), "KRI-001", "Test");
        assertThat(k.classify(BigDecimal.TEN)).isNull();
    }

    @Test
    void recordMeasurementThrowsWhenUnconfigured() {
        var k = new KeyRiskIndicator(new Project("p", "P"), "KRI-001", "Test");
        assertThatThrownBy(() -> k.recordMeasurement(BigDecimal.TEN, Instant.now()))
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
