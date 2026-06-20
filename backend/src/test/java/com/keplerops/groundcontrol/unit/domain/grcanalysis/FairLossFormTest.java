package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairLossForm;
import org.junit.jupiter.api.Test;

class FairLossFormTest {

    @Test
    void jsonKey_roundTripsThroughFromJsonKey() {
        for (FairLossForm form : FairLossForm.values()) {
            assertThat(FairLossForm.fromJsonKey(form.jsonKey())).isEqualTo(form);
        }
    }

    @Test
    void fromJsonKey_knownKeys_mapToExpectedForms() {
        assertThat(FairLossForm.fromJsonKey("productivity_loss")).isEqualTo(FairLossForm.PRODUCTIVITY);
        assertThat(FairLossForm.fromJsonKey("response_cost")).isEqualTo(FairLossForm.RESPONSE);
        assertThat(FairLossForm.fromJsonKey("replacement_cost")).isEqualTo(FairLossForm.REPLACEMENT);
        assertThat(FairLossForm.fromJsonKey("competitive_advantage_loss"))
                .isEqualTo(FairLossForm.COMPETITIVE_ADVANTAGE);
        assertThat(FairLossForm.fromJsonKey("fines_and_judgments")).isEqualTo(FairLossForm.FINES_AND_JUDGMENTS);
        assertThat(FairLossForm.fromJsonKey("reputation_damage")).isEqualTo(FairLossForm.REPUTATION);
    }

    @Test
    void fromJsonKey_unknownOrBlankOrNull_returnsNull() {
        assertThat(FairLossForm.fromJsonKey("not_a_loss_form")).isNull();
        assertThat(FairLossForm.fromJsonKey("")).isNull();
        assertThat(FairLossForm.fromJsonKey("  ")).isNull();
        assertThat(FairLossForm.fromJsonKey(null)).isNull();
    }
}
