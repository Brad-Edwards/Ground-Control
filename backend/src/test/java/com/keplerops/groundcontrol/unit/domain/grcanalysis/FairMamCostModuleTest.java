package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairMamCostModule;
import org.junit.jupiter.api.Test;

class FairMamCostModuleTest {

    @Test
    void thereAreExactlyTenFairMamCostModules() {
        // FAIR-MAM (FAIR Institute) is composed of ten primary cost modules.
        assertThat(FairMamCostModule.values()).hasSize(10);
    }

    @Test
    void jsonKey_roundTripsThroughFromJsonKey() {
        for (FairMamCostModule module : FairMamCostModule.values()) {
            assertThat(FairMamCostModule.fromJsonKey(module.jsonKey())).isEqualTo(module);
        }
    }

    @Test
    void fromJsonKey_knownKeys_mapToExpectedModules() {
        assertThat(FairMamCostModule.fromJsonKey("information_privacy"))
                .isEqualTo(FairMamCostModule.INFORMATION_PRIVACY);
        assertThat(FairMamCostModule.fromJsonKey("proprietary_data_loss"))
                .isEqualTo(FairMamCostModule.PROPRIETARY_DATA_LOSS);
        assertThat(FairMamCostModule.fromJsonKey("business_interruption"))
                .isEqualTo(FairMamCostModule.BUSINESS_INTERRUPTION);
        assertThat(FairMamCostModule.fromJsonKey("cyber_extortion")).isEqualTo(FairMamCostModule.CYBER_EXTORTION);
        assertThat(FairMamCostModule.fromJsonKey("network_security")).isEqualTo(FairMamCostModule.NETWORK_SECURITY);
        assertThat(FairMamCostModule.fromJsonKey("financial_fraud")).isEqualTo(FairMamCostModule.FINANCIAL_FRAUD);
        assertThat(FairMamCostModule.fromJsonKey("media_content")).isEqualTo(FairMamCostModule.MEDIA_CONTENT);
        assertThat(FairMamCostModule.fromJsonKey("hardware_bricking")).isEqualTo(FairMamCostModule.HARDWARE_BRICKING);
        assertThat(FairMamCostModule.fromJsonKey("post_breach_security_improvements"))
                .isEqualTo(FairMamCostModule.POST_BREACH_SECURITY_IMPROVEMENTS);
        assertThat(FairMamCostModule.fromJsonKey("reputational_damage"))
                .isEqualTo(FairMamCostModule.REPUTATIONAL_DAMAGE);
    }

    @Test
    void fromJsonKey_unknownOrBlankOrNull_returnsNull() {
        assertThat(FairMamCostModule.fromJsonKey("productivity"))
                .isNull(); // that is an O-RT form, not a FAIR-MAM module
        assertThat(FairMamCostModule.fromJsonKey("not_a_module")).isNull();
        assertThat(FairMamCostModule.fromJsonKey("")).isNull();
        assertThat(FairMamCostModule.fromJsonKey("  ")).isNull();
        assertThat(FairMamCostModule.fromJsonKey(null)).isNull();
    }
}
