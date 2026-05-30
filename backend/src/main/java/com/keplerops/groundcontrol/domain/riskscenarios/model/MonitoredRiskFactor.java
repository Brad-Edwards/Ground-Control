package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GC-T015: typed monitored risk factor on a {@link TreatmentPlan}.
 *
 * <p>Each entry records (1) the factor being monitored, (2) the change
 * category it belongs to per NIST §3.4, and (3) the cadence at which the
 * factor is re-checked. The cadence is an ISO-8601 duration in {@code cadence}
 * (e.g. {@code P30D}, {@code P3M}) so consumers can apply it deterministically.
 *
 * <p>This is the typed shape behind GC-T015's "monitored risk factors" clause —
 * the prior generic {@code List<String>} is preserved through the JSON converter
 * but new writes use this contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MonitoredRiskFactor(
        @NotBlank @Size(max = 200) String label,
        @NotNull ReassessmentTriggerCategory category,
        @Size(max = 50) String cadence,
        @Size(max = 1000) String notes) {}
