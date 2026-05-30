package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * GC-T007: Three-band classification for a Key Risk Indicator measurement.
 *
 * <p>{@link #GREEN} — within tolerance; nominal. {@link #YELLOW} — approaching
 * breach; warn. {@link #RED} — breach; triggers a reassessment signal through
 * {@code ReassessmentSignalService} via {@code KRI_BREACH}.
 */
public enum KriThresholdBand {
    GREEN,
    YELLOW,
    RED;

    /** True for bands that should fire a reassessment signal when entered. */
    public boolean isBreach() {
        return this == RED;
    }
}
