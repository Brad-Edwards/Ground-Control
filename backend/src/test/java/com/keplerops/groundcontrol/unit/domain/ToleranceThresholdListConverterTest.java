package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.ToleranceThresholdListConverter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToleranceThresholdListConverterTest {

    private final ToleranceThresholdListConverter converter = new ToleranceThresholdListConverter();

    @Test
    void roundTripsToleranceThresholds() {
        var thresholds = List.of(
                new ToleranceThreshold(
                        "data-breach", "annualized_loss_expectancy.likely", 500000.0, "USD", "USD", null, null, "ALE"),
                new ToleranceThreshold(
                        null, "risk_level", null, null, null, "HIGH", List.of("LOW", "MODERATE", "HIGH"), "band"));

        String json = converter.convertToDatabaseColumn(thresholds);
        List<ToleranceThreshold> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).metricPath()).isEqualTo("annualized_loss_expectancy.likely");
        assertThat(restored.get(0).maxQuantitativeValue()).isEqualTo(500000.0);
        assertThat(restored.get(1).maxOrdinalValue()).isEqualTo("HIGH");
        assertThat(restored.get(1).orderedScale()).containsExactly("LOW", "MODERATE", "HIGH");
    }

    @Test
    void nullRoundTrips() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
