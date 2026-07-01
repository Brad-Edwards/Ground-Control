package com.keplerops.groundcontrol.unit.infrastructure.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.infrastructure.campaign.EvidenceCampaignProperties;
import org.junit.jupiter.api.Test;

class EvidenceCampaignPropertiesTest {

    @Test
    void appliesDefaultCronsWhenBlank() {
        var props = new EvidenceCampaignProperties(true, "  ", null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.cron()).isEqualTo("0 0 * * * *");
        assertThat(props.pruneCron()).isEqualTo("0 30 3 * * *");
    }

    @Test
    void keepsProvidedCrons() {
        var props = new EvidenceCampaignProperties(false, "0 0 1 * * *", "0 0 2 * * *");

        assertThat(props.enabled()).isFalse();
        assertThat(props.cron()).isEqualTo("0 0 1 * * *");
        assertThat(props.pruneCron()).isEqualTo("0 0 2 * * *");
    }
}
