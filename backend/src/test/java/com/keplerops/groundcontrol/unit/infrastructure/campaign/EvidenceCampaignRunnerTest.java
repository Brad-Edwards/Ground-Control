package com.keplerops.groundcontrol.unit.infrastructure.campaign;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceCampaignService;
import com.keplerops.groundcontrol.infrastructure.campaign.EvidenceCampaignRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceCampaignRunnerTest {

    @Mock
    private EvidenceCampaignService service;

    @InjectMocks
    private EvidenceCampaignRunner runner;

    @Test
    void runDueCampaignsTickDelegatesToService() {
        when(service.runDueCampaigns(any())).thenReturn(2);

        runner.runDueCampaigns();

        verify(service).runDueCampaigns(any());
    }

    @Test
    void pruneTickDelegatesToService() {
        when(service.pruneExpiredRuns(any())).thenReturn(1);

        runner.pruneExpiredRuns();

        verify(service).pruneExpiredRuns(any());
    }
}
