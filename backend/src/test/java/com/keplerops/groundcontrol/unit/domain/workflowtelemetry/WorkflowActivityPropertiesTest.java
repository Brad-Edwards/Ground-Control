package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowActivityProperties;
import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkflowActivityPropertiesTest {

    @Test
    void defaultsArePositiveAndBounded() {
        var properties = new WorkflowActivityProperties();

        assertThat(validatorViolations(properties)).isZero();
        assertThat(properties.getStallThreshold()).isPositive();
        assertThat(properties.getMaxOpenRuns()).isBetween(1, 500);
        assertThat(properties.getRecentRuns()).isBetween(1, 50);
    }

    @Test
    void zeroStallThresholdIsRejected() {
        var properties = new WorkflowActivityProperties();
        properties.setStallThreshold(Duration.ZERO);

        assertThat(validatorViolations(properties)).isGreaterThan(0);
    }

    private static int validatorViolations(WorkflowActivityProperties properties) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(properties).size();
        }
    }
}
