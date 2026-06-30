package com.keplerops.groundcontrol.unit.domain.evidence;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.service.ControlLinkService;
import com.keplerops.groundcontrol.domain.controls.service.ControlService;
import com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaign;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaignRun;
import com.keplerops.groundcontrol.domain.evidence.campaign.repository.EvidenceCampaignRepository;
import com.keplerops.groundcontrol.domain.evidence.campaign.repository.EvidenceCampaignRunRepository;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.CreateEvidenceCampaignCommand;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceCampaignService;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceEndpointPolicy;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.UpdateEvidenceCampaignCommand;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignRunStatus;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignStatus;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionAdapter;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionError;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionOutputSchema;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionRateLimit;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionRequest;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionResult;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionStatus;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceConnectionConfig;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.service.CreateEvidenceArtifactCommand;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceArtifactService;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceCollectionAdapterRegistry;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceCampaignServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-000000000900");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    @Mock
    private EvidenceCampaignRepository repository;

    @Mock
    private EvidenceCampaignRunRepository runRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private EvidenceArtifactService evidenceArtifactService;

    @Mock
    private EvidenceCollectionAdapterRegistry adapterRegistry;

    @Mock
    private ControlLinkService controlLinkService;

    @Mock
    private ControlService controlService;

    @Mock
    private EvidenceEndpointPolicy endpointPolicy;

    @InjectMocks
    private EvidenceCampaignService service;

    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        // Default: the endpoint resolves to a public, allowed address. Tests that exercise the
        // SSRF re-validation override this stub.
        lenient()
                .when(endpointPolicy.validateAndResolve(any()))
                .thenReturn(List.of(InetAddress.getByName("93.184.216.34")));
    }

    private EvidenceCampaign campaign() {
        var campaign = new EvidenceCampaign(
                project,
                "CAMP-0001",
                "Quarterly IAM evidence",
                EvidenceCampaignFrequency.DAILY,
                "iam-collector",
                "iam.users",
                "iam-prod",
                "https://iam.example.com",
                "vault://iam/prod",
                NOW);
        setField(campaign, "id", CAMPAIGN_ID);
        setField(campaign, "createdAt", NOW.minusSeconds(3600));
        return campaign;
    }

    private CreateEvidenceCampaignCommand createCommand(String endpoint) {
        return new CreateEvidenceCampaignCommand(
                PROJECT_ID,
                "CAMP-0001",
                "Quarterly IAM evidence",
                EvidenceCampaignFrequency.DAILY,
                "iam-collector",
                "iam.users",
                null,
                "iam-prod",
                endpoint,
                "vault://iam/prod",
                Map.of(),
                null,
                30,
                NOW);
    }

    private EvidenceCollectionResult result(
            EvidenceCollectionStatus status,
            List<CreateEvidenceArtifactCommand> artifacts,
            List<EvidenceCollectionError> errors) {
        var schema = new EvidenceCollectionOutputSchema("s1", "1.0", EvidenceType.ASSURANCE_CONCLUSION, Map.of());
        var rateLimit = new EvidenceCollectionRateLimit(100, Duration.ofMinutes(1), 99, NOW.plusSeconds(60));
        return new EvidenceCollectionResult(
                "iam-collector", "1.0", status, schema, NOW, artifacts, List.of(), errors, rateLimit);
    }

    private CreateEvidenceArtifactCommand artifactCommand(String uid) {
        return new CreateEvidenceArtifactCommand(
                PROJECT_ID, uid, "t", "s", EvidenceType.ASSURANCE_CONCLUSION, "m", NOW, null, null, null, List.of());
    }

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private EvidenceArtifact artifactWithId(UUID id) {
        var artifact = org.mockito.Mockito.mock(EvidenceArtifact.class);
        when(artifact.getId()).thenReturn(id);
        return artifact;
    }

    @Test
    void createPersistsActiveCampaign() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(PROJECT_ID, "CAMP-0001")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.create(createCommand("https://iam.example.com"));

        assertThat(saved.getStatus()).isEqualTo(EvidenceCampaignStatus.ACTIVE);
        assertThat(saved.getNextRunAt()).isEqualTo(NOW);
        assertThat(saved.getRetentionDays()).isEqualTo(30);
    }

    @Test
    void createRejectsDuplicateUid() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(PROJECT_ID, "CAMP-0001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createCommand("https://iam.example.com")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createRejectsInvalidEndpoint() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(PROJECT_ID, "CAMP-0001")).thenReturn(false);
        doThrow(new DomainValidationException("connectionEndpoint must be a valid URI"))
                .when(endpointPolicy)
                .validate("ht tp://bad endpoint");

        assertThatThrownBy(() -> service.create(createCommand("ht tp://bad endpoint")))
                .isInstanceOf(DomainValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsUnknownTargetControl() {
        UUID controlId = UUID.randomUUID();
        var command = new CreateEvidenceCampaignCommand(
                PROJECT_ID,
                "CAMP-0001",
                "Quarterly IAM evidence",
                EvidenceCampaignFrequency.DAILY,
                "iam-collector",
                "iam.users",
                null,
                "iam-prod",
                "https://iam.example.com",
                "vault://iam/prod",
                Map.of(),
                List.of(controlId),
                30,
                NOW);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(PROJECT_ID, "CAMP-0001")).thenReturn(false);
        when(controlService.getById(PROJECT_ID, controlId))
                .thenThrow(new NotFoundException("Control not found: " + controlId));

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void executeCampaignLinksArtifactsToTargetControls() {
        var campaign = campaign();
        UUID controlA = UUID.randomUUID();
        UUID controlB = UUID.randomUUID();
        campaign.setTargetControlIds(List.of(controlA, controlB));
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any()))
                .thenReturn(result(EvidenceCollectionStatus.SUCCEEDED, List.of(artifactCommand("EVD-1")), List.of()));
        var artifact = artifactWithId(UUID.randomUUID());
        when(evidenceArtifactService.create(any())).thenReturn(artifact);
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        assertThat(run.getStatus()).isEqualTo(EvidenceCampaignRunStatus.COMPLETED);
        // C5: each produced artifact is linked to every target control as evidence.
        verify(controlLinkService, times(2))
                .create(eq(PROJECT_ID), any(UUID.class), any(CreateControlLinkCommand.class));
    }

    @Test
    void executeCampaignForwardsConfiguredSchemaToAdapter() {
        var campaign = campaign();
        campaign.setSchemaId("iam-access-observation");
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        ArgumentCaptor<EvidenceCollectionRequest> reqCaptor = ArgumentCaptor.forClass(EvidenceCollectionRequest.class);
        when(adapter.collect(reqCaptor.capture()))
                .thenReturn(result(EvidenceCollectionStatus.SUCCEEDED, List.of(), List.of()));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        // The configured schemaId reaches the adapter so it can honor the selected evidence schema.
        assertThat(reqCaptor.getValue().options())
                .containsEntry(EvidenceCollectionRequest.SCHEMA_OPTION, "iam-access-observation");
    }

    @Test
    void executeCampaignIsPartialWhenArtifactPersistFails() {
        var campaign = campaign();
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any()))
                .thenReturn(result(
                        EvidenceCollectionStatus.SUCCEEDED,
                        List.of(artifactCommand("EVD-1"), artifactCommand("EVD-2")),
                        List.of()));
        var artifact1 = artifactWithId(UUID.randomUUID());
        when(evidenceArtifactService.create(any()))
                .thenReturn(artifact1)
                .thenThrow(new ConflictException("duplicate uid"));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        assertThat(run.getStatus()).isEqualTo(EvidenceCampaignRunStatus.PARTIAL);
        assertThat(run.getArtifactCount()).isEqualTo(1);
        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getSanitizedError()).contains("persist_error");
    }

    @Test
    void updateRejectsBlankRequiredField() {
        var campaign = campaign();
        when(repository.findByIdAndProjectId(CAMPAIGN_ID, PROJECT_ID)).thenReturn(java.util.Optional.of(campaign));
        var blankCredential =
                new UpdateEvidenceCampaignCommand(null, null, null, null, null, null, "   ", null, null, null);

        assertThatThrownBy(() -> service.update(PROJECT_ID, CAMPAIGN_ID, blankCredential))
                .isInstanceOf(DomainValidationException.class);
        // The invalid value must not overwrite valid aggregate state.
        verify(repository, never()).save(any());
    }

    @Test
    void executeCampaignPinsResolvedAddressesIntoConnection() {
        var campaign = campaign();
        when(endpointPolicy.validateAndResolve("https://iam.example.com")).thenReturn(List.of(addr("93.184.216.34")));
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        ArgumentCaptor<EvidenceCollectionRequest> reqCaptor = ArgumentCaptor.forClass(EvidenceCollectionRequest.class);
        when(adapter.collect(reqCaptor.capture()))
                .thenReturn(result(EvidenceCollectionStatus.SUCCEEDED, List.of(), List.of()));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        // The execution-time-validated address is pinned into the connection so a conforming adapter
        // connects to it rather than re-resolving the host (DNS-rebinding defense).
        Object pinned =
                reqCaptor.getValue().connection().settings().get(EvidenceConnectionConfig.PINNED_ADDRESSES_SETTING);
        assertThat(pinned)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("93.184.216.34");
    }

    @Test
    void executeCampaignFailsWhenEndpointReboundsToInternalAddress() {
        var campaign = campaign();
        // The host validated as public at store time but now resolves to internal space: the
        // execution-time re-validation rejects it, and the run is recorded FAILED rather than calling out.
        when(endpointPolicy.validateAndResolve("https://iam.example.com"))
                .thenThrow(new DomainValidationException("connectionEndpoint must not resolve to a private address"));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        assertThat(run.getStatus()).isEqualTo(EvidenceCampaignRunStatus.FAILED);
        assertThat(run.getErrorCount()).isGreaterThanOrEqualTo(1);
        assertThat(run.getArtifactCount()).isZero();
    }

    @Test
    void executeCampaignRedactsProviderErrorText() {
        var campaign = campaign();
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        var secretBearing = new EvidenceCollectionError(
                "provider_error", "auth failed for Bearer sk-supersecrettoken0123456789ABCDEF", null, true, Map.of());
        when(adapter.collect(any()))
                .thenReturn(result(EvidenceCollectionStatus.PARTIAL, List.of(), List.of(secretBearing)));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        // Controlled code retained; the token-bearing provider message is redacted.
        assertThat(run.getSanitizedError()).contains("provider_error").doesNotContain("sk-supersecrettoken");
    }

    @Test
    void executeCampaignStoresOnlyExceptionCategoryOnFailure() {
        var campaign = campaign();
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any())).thenThrow(new RuntimeException("connect to https://user:s3cr3t@10.0.0.1 failed"));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        // Raw exception detail (which can carry the endpoint URI with userinfo) is kept out entirely.
        assertThat(run.getSanitizedError()).isEqualTo("run_error: RuntimeException");
        assertThat(run.getSanitizedError()).doesNotContain("s3cr3t").doesNotContain("10.0.0.1");
    }

    @Test
    void getByIdReturnsCampaign() {
        var campaign = campaign();
        when(repository.findByIdAndProjectId(CAMPAIGN_ID, PROJECT_ID)).thenReturn(java.util.Optional.of(campaign));

        assertThat(service.getById(PROJECT_ID, CAMPAIGN_ID)).isSameAs(campaign);
    }

    @Test
    void listByProjectReturnsCampaigns() {
        var campaign = campaign();
        when(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(campaign));

        assertThat(service.listByProject(PROJECT_ID)).containsExactly(campaign);
    }

    @Test
    void listRunsReturnsRunsForCampaign() {
        var campaign = campaign();
        when(repository.findByIdAndProjectId(CAMPAIGN_ID, PROJECT_ID)).thenReturn(java.util.Optional.of(campaign));
        var run = org.mockito.Mockito.mock(EvidenceCampaignRun.class);
        when(runRepository.findByCampaignIdAndProjectIdOrderByWindowStartDesc(CAMPAIGN_ID, PROJECT_ID))
                .thenReturn(List.of(run));

        assertThat(service.listRuns(PROJECT_ID, CAMPAIGN_ID)).containsExactly(run);
    }

    @Test
    void updateAppliesProvidedFields() {
        var campaign = campaign();
        when(repository.findByIdAndProjectId(CAMPAIGN_ID, PROJECT_ID)).thenReturn(java.util.Optional.of(campaign));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var command = new UpdateEvidenceCampaignCommand(
                "Renamed",
                EvidenceCampaignFrequency.WEEKLY,
                "iam.roles",
                "schema-1",
                "iam-stage",
                "https://stage.example.com",
                "vault://iam/stage",
                Map.of("region", "eu"),
                List.of(),
                60);

        var updated = service.update(PROJECT_ID, CAMPAIGN_ID, command);

        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getFrequency()).isEqualTo(EvidenceCampaignFrequency.WEEKLY);
        assertThat(updated.getScopeType()).isEqualTo("iam.roles");
        assertThat(updated.getConnectionEndpoint()).isEqualTo("https://stage.example.com");
        assertThat(updated.getRetentionDays()).isEqualTo(60);
        verify(endpointPolicy).validate("https://stage.example.com");
    }

    @Test
    void triggerExecutesImmediatelyAndReturnsRun() {
        var campaign = campaign();
        when(repository.findByIdAndProjectId(CAMPAIGN_ID, PROJECT_ID)).thenReturn(java.util.Optional.of(campaign));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any())).thenReturn(result(EvidenceCollectionStatus.SUCCEEDED, List.of(), List.of()));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.trigger(PROJECT_ID, CAMPAIGN_ID);

        assertThat(run.getStatus()).isEqualTo(EvidenceCampaignRunStatus.COMPLETED);
        assertThat(campaign.getLastRunAt()).isNotNull();
    }

    @Test
    void pauseAndResumeToggleStatus() {
        var campaign = campaign();
        when(repository.findByIdAndProjectId(CAMPAIGN_ID, PROJECT_ID)).thenReturn(java.util.Optional.of(campaign));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.pause(PROJECT_ID, CAMPAIGN_ID).getStatus()).isEqualTo(EvidenceCampaignStatus.PAUSED);
        assertThat(service.resume(PROJECT_ID, CAMPAIGN_ID).getStatus()).isEqualTo(EvidenceCampaignStatus.ACTIVE);
    }

    @Test
    void frequencyAdvanceUsesCalendarSteps() {
        var base = Instant.parse("2026-01-31T00:00:00Z");
        assertThat(EvidenceCampaignFrequency.DAILY.advance(base)).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(EvidenceCampaignFrequency.WEEKLY.advance(base)).isEqualTo(Instant.parse("2026-02-07T00:00:00Z"));
        assertThat(EvidenceCampaignFrequency.MONTHLY.advance(base)).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
        assertThat(EvidenceCampaignFrequency.QUARTERLY.advance(base)).isEqualTo(Instant.parse("2026-04-30T00:00:00Z"));
    }

    @Test
    void runDueCampaignsExecutesClaimedCampaign() {
        var campaign = campaign();
        when(repository.findByStatusAndNextRunAtLessThanEqual(EvidenceCampaignStatus.ACTIVE, NOW))
                .thenReturn(List.of(campaign));
        when(repository.markClaimedIfDue(eq(CAMPAIGN_ID), eq(NOW), any(), eq(NOW), eq(EvidenceCampaignStatus.ACTIVE)))
                .thenReturn(1);
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any())).thenReturn(result(EvidenceCollectionStatus.SUCCEEDED, List.of(), List.of()));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int executed = service.runDueCampaigns(NOW);

        assertThat(executed).isEqualTo(1);
        verify(adapter).collect(any());
    }

    @Test
    void runDueCampaignsSkipsWhenStatusAwareClaimNotWon() {
        // A due ACTIVE campaign whose status-aware claim affects 0 rows is skipped without executing.
        // The conditional claim returns 0 in two cases it deliberately folds together: a concurrent
        // sweep already advanced the cursor, or the campaign was paused between the due-select and the
        // claim (status no longer ACTIVE). Both are observationally identical at the claim boundary.
        var campaign = campaign();
        when(repository.findByStatusAndNextRunAtLessThanEqual(EvidenceCampaignStatus.ACTIVE, NOW))
                .thenReturn(List.of(campaign));
        when(repository.markClaimedIfDue(eq(CAMPAIGN_ID), eq(NOW), any(), eq(NOW), eq(EvidenceCampaignStatus.ACTIVE)))
                .thenReturn(0);

        int executed = service.runDueCampaigns(NOW);

        assertThat(executed).isZero();
        // The claim predicate is status-aware: ACTIVE is passed as the expected status, which is what
        // makes pause an atomic boundary the sweep honors (distinct from a plain optimistic-cursor claim).
        verify(repository)
                .markClaimedIfDue(eq(CAMPAIGN_ID), eq(NOW), any(), eq(NOW), eq(EvidenceCampaignStatus.ACTIVE));
        verify(adapterRegistry, never()).getAdapter(any());
    }

    @Test
    void runDueCampaignsCoalescesOverdueWindowsIntoSingleRun() {
        // A campaign that has been due for ~30 daily periods (engine downtime).
        Instant overdue = NOW.minus(Duration.ofDays(30));
        var campaign = new EvidenceCampaign(
                project,
                "CAMP-0001",
                "Quarterly IAM evidence",
                EvidenceCampaignFrequency.DAILY,
                "iam-collector",
                "iam.users",
                "iam-prod",
                "https://iam.example.com",
                "vault://iam/prod",
                overdue);
        setField(campaign, "id", CAMPAIGN_ID);
        when(repository.findByStatusAndNextRunAtLessThanEqual(EvidenceCampaignStatus.ACTIVE, NOW))
                .thenReturn(List.of(campaign));
        ArgumentCaptor<Instant> nextCaptor = ArgumentCaptor.forClass(Instant.class);
        when(repository.markClaimedIfDue(
                        eq(CAMPAIGN_ID), eq(overdue), nextCaptor.capture(), eq(NOW), eq(EvidenceCampaignStatus.ACTIVE)))
                .thenReturn(1);
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any())).thenReturn(result(EvidenceCollectionStatus.SUCCEEDED, List.of(), List.of()));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int executed = service.runDueCampaigns(NOW);

        // Despite ~30 missed daily windows, the campaign collects exactly once and
        // its scheduling cursor jumps past now instead of emitting one overlapping
        // window per missed day.
        assertThat(executed).isEqualTo(1);
        verify(adapter, times(1)).collect(any());
        assertThat(nextCaptor.getValue()).isAfter(NOW);
    }

    @Test
    void executeCampaignCompletesOnSuccess() {
        var campaign = campaign();
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any()))
                .thenReturn(result(
                        EvidenceCollectionStatus.SUCCEEDED,
                        List.of(artifactCommand("EVD-1"), artifactCommand("EVD-2")),
                        List.of()));
        var artifact1 = artifactWithId(UUID.randomUUID());
        var artifact2 = artifactWithId(UUID.randomUUID());
        when(evidenceArtifactService.create(any())).thenReturn(artifact1, artifact2);
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        assertThat(run.getStatus()).isEqualTo(EvidenceCampaignRunStatus.COMPLETED);
        assertThat(run.getArtifactCount()).isEqualTo(2);
        assertThat(run.getErrorCount()).isZero();
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void executeCampaignIsPartialWhenErrorsPresent() {
        var campaign = campaign();
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any()))
                .thenReturn(result(
                        EvidenceCollectionStatus.SUCCEEDED,
                        List.of(artifactCommand("EVD-1")),
                        List.of(new EvidenceCollectionError("E1", "partial failure", null, true, Map.of()))));
        var artifact = artifactWithId(UUID.randomUUID());
        when(evidenceArtifactService.create(any())).thenReturn(artifact);
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        assertThat(run.getStatus()).isEqualTo(EvidenceCampaignRunStatus.PARTIAL);
        assertThat(run.getArtifactCount()).isEqualTo(1);
        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getSanitizedError()).contains("partial failure");
    }

    @Test
    void executeCampaignFailsWhenAdapterThrows() {
        var campaign = campaign();
        var adapter = org.mockito.Mockito.mock(EvidenceCollectionAdapter.class);
        when(adapterRegistry.getAdapter("iam-collector")).thenReturn(adapter);
        when(adapter.collect(any())).thenThrow(new RuntimeException("connection refused"));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var run = service.executeCampaign(campaign, NOW.minusSeconds(86400), NOW);

        assertThat(run.getStatus()).isEqualTo(EvidenceCampaignRunStatus.FAILED);
        assertThat(run.getErrorCount()).isGreaterThanOrEqualTo(1);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void pruneExpiredRunsDeletesPerRetention() {
        var campaign = campaign();
        campaign.setRetentionDays(30);
        when(repository.findAll()).thenReturn(List.of(campaign));
        when(runRepository.deleteFinishedRunsBefore(eq(CAMPAIGN_ID), any())).thenReturn(3);

        int pruned = service.pruneExpiredRuns(NOW);

        assertThat(pruned).isEqualTo(3);
    }
}
