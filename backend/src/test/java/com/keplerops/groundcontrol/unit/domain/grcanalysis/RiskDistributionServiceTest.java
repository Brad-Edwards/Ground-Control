package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskDistributionGroupBy;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskDistributionResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskDistributionService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskRegisterStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskDistributionServiceTest {

    @Mock
    private RiskRegisterRecordRepository registerRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @InjectMocks
    private RiskDistributionService service;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
    }

    private RiskRegisterRecord buildRecord(
            String uid, RiskRegisterStatus status, String owner, java.util.List<String> tags) {
        var r = new RiskRegisterRecord(project, uid, uid + " title");
        setField(r, "id", UUID.randomUUID());
        setField(r, "status", status);
        if (owner != null) {
            r.setOwner(owner);
        }
        if (tags != null) {
            r.setCategoryTags(tags);
        }
        return r;
    }

    @Test
    void groupByStatus_countsByEnumName() {
        var open = buildRecord("RR-1", RiskRegisterStatus.IDENTIFIED, null, null);
        var closed = buildRecord("RR-2", RiskRegisterStatus.CLOSED, null, null);
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(open, closed));

        RiskDistributionResult result = service.distribute(projectId, null, RiskDistributionGroupBy.STATUS);

        assertThat(result.analysisKind()).isEqualTo("risk_distribution");
        assertThat(result.derivationMethod()).isEqualTo("risk-register-distribution-v1");
        assertThat(result.inputs().groupBy()).isEqualTo("STATUS");
        assertThat(result.counts().totalRecords()).isEqualTo(2);
        assertThat(result.buckets())
                .extracting(RiskDistributionResult.DistributionBucket::key)
                .contains("IDENTIFIED", "CLOSED");
    }

    @Test
    void groupByOwner_skipsBlankOwnersWithLimitation() {
        var with = buildRecord("RR-1", RiskRegisterStatus.IDENTIFIED, "alice", null);
        var without = buildRecord("RR-2", RiskRegisterStatus.IDENTIFIED, null, null);
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(with, without));

        RiskDistributionResult result = service.distribute(projectId, null, RiskDistributionGroupBy.OWNER);

        assertThat(result.buckets())
                .extracting(RiskDistributionResult.DistributionBucket::key)
                .containsExactlyInAnyOrder("alice", "UNCLASSIFIED");
        assertThat(result.counts().recordsUnclassified()).isEqualTo(1);
        assertThat(result.limitations()).isNotEmpty();
    }

    @Test
    void groupByCategory_splitsTagsAndKeepsAllPerRecord() {
        var multi = buildRecord("RR-1", RiskRegisterStatus.IDENTIFIED, null, List.of("data", "privacy"));
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(multi));

        RiskDistributionResult result = service.distribute(projectId, null, RiskDistributionGroupBy.CATEGORY);

        assertThat(result.buckets())
                .extracting(RiskDistributionResult.DistributionBucket::key)
                .containsExactlyInAnyOrder("data", "privacy");
    }

    @Test
    void groupByAssetCriticality_alwaysEmitsCarveOutLimitation() {
        var any = buildRecord("RR-1", RiskRegisterStatus.IDENTIFIED, null, null);
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(any));

        RiskDistributionResult result = service.distribute(projectId, null, RiskDistributionGroupBy.ASSET_CRITICALITY);

        assertThat(result.limitations()).anyMatch(s -> s.contains("asset-criticality grouping"));
        assertThat(result.buckets())
                .extracting(RiskDistributionResult.DistributionBucket::key)
                .containsExactly("UNCLASSIFIED");
    }

    @Test
    void projectNotFound_throws() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.distribute(missing, null, RiskDistributionGroupBy.STATUS))
                .isInstanceOf(NotFoundException.class);
    }
}
