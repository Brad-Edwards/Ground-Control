package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.state.KriThresholdBand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-T007: Key Risk Indicator aggregate.
 *
 * <p>A KRI tracks a single numeric measurement against threshold bands. The
 * thresholds are expressed as two breakpoints on a single numeric axis:
 * <pre>
 *   value &lt; yellowThreshold        → GREEN
 *   value &gt;= yellowThreshold &amp;&amp; value &lt; redThreshold → YELLOW
 *   value &gt;= redThreshold        → RED  (breach)
 * </pre>
 * (Set {@code direction = LOWER_IS_WORSE} to flip the inequalities — useful
 * for KRIs where smaller is worse, e.g. patch coverage. The two accepted
 * values are exactly {@code HIGHER_IS_WORSE} (default) and {@code LOWER_IS_WORSE};
 * the {@link com.keplerops.groundcontrol.domain.riskscenarios.service.KeyRiskIndicatorService}
 * rejects any other string at the write boundary so a typo like
 * {@code LOWER_IS_BETTER} or {@code lower_is_worse} cannot land and silently
 * mis-band measurements.)
 *
 * <p>The KRI optionally links to a risk register record or risk scenario.
 * RED measurements publish a synchronous {@link
 * com.keplerops.groundcontrol.domain.riskscenarios.events.KriBreachedEvent}
 * to {@code ReassessmentSignalService} which fans out the
 * {@link com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory#KRI_BREACH}
 * reassessment signal.
 */
@Entity
@Audited
@Table(name = "key_risk_indicator", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "uid"}))
public class KeyRiskIndicator extends BaseEntity {

    /** Default direction — value above {@code redThreshold} is RED. */
    public static final String DIRECTION_HIGHER_IS_WORSE = "HIGHER_IS_WORSE";

    /** Inverted direction — value below {@code redThreshold} is RED. */
    public static final String DIRECTION_LOWER_IS_WORSE = "LOWER_IS_WORSE";

    /**
     * Closed vocabulary for {@code direction}. The aggregate stores direction
     * as a String so future organizations can extend without an Envers schema
     * bump, but the write boundary (service layer) rejects any string not in
     * this set so a typo like {@code LOWER_IS_BETTER} or {@code lower_is_worse}
     * cannot land and silently flip classification semantics.
     */
    public static final Set<String> VALID_DIRECTIONS = Set.of(DIRECTION_HIGHER_IS_WORSE, DIRECTION_LOWER_IS_WORSE);

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 50)
    private String uid;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "metric_unit", length = 50)
    private String metricUnit;

    @Column(name = "yellow_threshold", precision = 38, scale = 10)
    private BigDecimal yellowThreshold;

    @Column(name = "red_threshold", precision = 38, scale = 10)
    private BigDecimal redThreshold;

    /**
     * {@code HIGHER_IS_WORSE} (default) — value above redThreshold is RED.
     * {@code LOWER_IS_WORSE} — value below redThreshold is RED.
     * Stored as a String so future organizations can extend without an
     * Envers schema bump, but the write boundary (service layer) rejects
     * anything outside {@link #VALID_DIRECTIONS} so a typo cannot silently
     * flip classification polarity.
     */
    @Column(name = "direction", nullable = false, length = 20)
    private String direction = DIRECTION_HIGHER_IS_WORSE;

    @Column(name = "owner", length = 200)
    private String owner;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "risk_register_record_id")
    private RiskRegisterRecord riskRegisterRecord;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "risk_scenario_id")
    private RiskScenario riskScenario;

    @Column(name = "current_value", precision = 38, scale = 10)
    private BigDecimal currentValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_band", length = 10)
    private KriThresholdBand currentBand;

    @Column(name = "last_measured_at")
    private Instant lastMeasuredAt;

    protected KeyRiskIndicator() {
        // JPA
    }

    public KeyRiskIndicator(Project project, String uid, String name) {
        this.project = project;
        this.uid = uid;
        this.name = name;
    }

    /**
     * Classify {@code value} against the configured thresholds. Returns null
     * when either threshold is unset (the KRI is not yet calibrated).
     */
    public KriThresholdBand classify(BigDecimal value) {
        if (value == null || yellowThreshold == null || redThreshold == null) {
            return null;
        }
        boolean higherWorse = !DIRECTION_LOWER_IS_WORSE.equals(direction);
        if (higherWorse) {
            if (value.compareTo(redThreshold) >= 0) {
                return KriThresholdBand.RED;
            }
            if (value.compareTo(yellowThreshold) >= 0) {
                return KriThresholdBand.YELLOW;
            }
            return KriThresholdBand.GREEN;
        }
        if (value.compareTo(redThreshold) <= 0) {
            return KriThresholdBand.RED;
        }
        if (value.compareTo(yellowThreshold) <= 0) {
            return KriThresholdBand.YELLOW;
        }
        return KriThresholdBand.GREEN;
    }

    /**
     * Apply a new measurement, classify it, and update the indicator state.
     * Returns the resulting band so callers can decide whether to publish a
     * reassessment signal without re-classifying.
     */
    public KriThresholdBand recordMeasurement(BigDecimal value, Instant measuredAt) {
        if (yellowThreshold == null || redThreshold == null) {
            throw new DomainValidationException("KRI thresholds are not configured");
        }
        var band = classify(value);
        this.currentValue = value;
        this.currentBand = band;
        this.lastMeasuredAt = measuredAt;
        return band;
    }

    public Project getProject() {
        return project;
    }

    public String getUid() {
        return uid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMetricUnit() {
        return metricUnit;
    }

    public void setMetricUnit(String metricUnit) {
        this.metricUnit = metricUnit;
    }

    public BigDecimal getYellowThreshold() {
        return yellowThreshold;
    }

    public void setYellowThreshold(BigDecimal yellowThreshold) {
        this.yellowThreshold = yellowThreshold;
    }

    public BigDecimal getRedThreshold() {
        return redThreshold;
    }

    public void setRedThreshold(BigDecimal redThreshold) {
        this.redThreshold = redThreshold;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public RiskRegisterRecord getRiskRegisterRecord() {
        return riskRegisterRecord;
    }

    public void setRiskRegisterRecord(RiskRegisterRecord riskRegisterRecord) {
        this.riskRegisterRecord = riskRegisterRecord;
    }

    public RiskScenario getRiskScenario() {
        return riskScenario;
    }

    public void setRiskScenario(RiskScenario riskScenario) {
        this.riskScenario = riskScenario;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public KriThresholdBand getCurrentBand() {
        return currentBand;
    }

    public Instant getLastMeasuredAt() {
        return lastMeasuredAt;
    }
}
