package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairFormOfLoss;
import org.junit.jupiter.api.Test;

class FairFormOfLossTest {

    @Test
    void thereAreExactlySixOrtFormsOfLoss() {
        // The Open Group Risk Taxonomy (O-RT) defines six forms of loss.
        assertThat(FairFormOfLoss.values()).hasSize(6);
    }

    @Test
    void jsonKey_roundTripsThroughFromJsonKey() {
        for (FairFormOfLoss form : FairFormOfLoss.values()) {
            assertThat(FairFormOfLoss.fromJsonKey(form.jsonKey())).isEqualTo(form);
        }
    }

    @Test
    void fromJsonKey_knownKeys_mapToExpectedForms() {
        assertThat(FairFormOfLoss.fromJsonKey("productivity")).isEqualTo(FairFormOfLoss.PRODUCTIVITY);
        assertThat(FairFormOfLoss.fromJsonKey("response")).isEqualTo(FairFormOfLoss.RESPONSE);
        assertThat(FairFormOfLoss.fromJsonKey("replacement")).isEqualTo(FairFormOfLoss.REPLACEMENT);
        assertThat(FairFormOfLoss.fromJsonKey("fines_and_judgments")).isEqualTo(FairFormOfLoss.FINES_AND_JUDGMENTS);
        assertThat(FairFormOfLoss.fromJsonKey("competitive_advantage")).isEqualTo(FairFormOfLoss.COMPETITIVE_ADVANTAGE);
        assertThat(FairFormOfLoss.fromJsonKey("reputation")).isEqualTo(FairFormOfLoss.REPUTATION);
    }

    @Test
    void fromJsonKey_unknownOrBlankOrNull_returnsNull() {
        assertThat(FairFormOfLoss.fromJsonKey("reputation_damage"))
                .isNull(); // that is a FAIR-MAM key, not an O-RT form
        assertThat(FairFormOfLoss.fromJsonKey("not_a_form")).isNull();
        assertThat(FairFormOfLoss.fromJsonKey("")).isNull();
        assertThat(FairFormOfLoss.fromJsonKey("  ")).isNull();
        assertThat(FairFormOfLoss.fromJsonKey(null)).isNull();
    }
}
