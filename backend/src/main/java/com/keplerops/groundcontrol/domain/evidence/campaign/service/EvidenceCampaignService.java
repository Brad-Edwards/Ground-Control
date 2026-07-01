package com.keplerops.groundcontrol.domain.evidence.campaign.service;

import com.keplerops.groundcontrol.domain.controls.service.ControlLinkService;
import com.keplerops.groundcontrol.domain.controls.service.ControlService;
import com.keplerops.groundcontrol.domain.controls.service.CreateControlLinkCommand;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkType;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaign;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaignRun;
import com.keplerops.groundcontrol.domain.evidence.campaign.repository.EvidenceCampaignRepository;
import com.keplerops.groundcontrol.domain.evidence.campaign.repository.EvidenceCampaignRunRepository;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignRunStatus;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignStatus;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionAdapter;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionError;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionRequest;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionResult;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionScope;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionStatus;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceConnectionConfig;
import com.keplerops.groundcontrol.domain.evidence.service.CreateEvidenceArtifactCommand;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceArtifactService;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceCollectionAdapterRegistry;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for scheduled evidence-collection campaigns per GC-S005.
 *
 * <p>Owns project-scoped CRUD over {@link EvidenceCampaign}, the pause/resume
 * lifecycle, manual triggering, the scheduled-sweep claim/execute loop, and
 * retention pruning of {@link EvidenceCampaignRun} rows. Execution invokes the
 * configured {@link EvidenceCollectionAdapter}, persists each collected result
 * as an {@code EvidenceArtifact} through {@link EvidenceArtifactService}, and
 * links produced artifacts to the campaign's target controls.
 *
 * <p>The campaign carries only a {@code credentialRef}; no raw secret is ever
 * logged, persisted, or surfaced. Run error summaries are length-bounded.
 *
 * <p><b>Transaction boundaries.</b> The CRUD/lifecycle methods ({@code create},
 * {@code update}, {@code pause}, {@code resume}) run in their own write
 * transaction; the read methods are {@code readOnly}. Sweep execution
 * ({@code runDueCampaigns}, {@code trigger}, {@code executeCampaign},
 * {@code pruneExpiredRuns}) is deliberately <em>not</em> wrapped in an ambient
 * transaction: each campaign claim, each run row, each artifact persist, and
 * each retention delete commits independently, so a failure collecting one
 * campaign (or one artifact) cannot roll back another campaign's already-recorded
 * run or already-persisted evidence. Entities are EAGER-fetched, so detaching
 * them from a surrounding transaction is safe.
 */
@Service
public class EvidenceCampaignService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCampaignService.class);
    private static final String FIELD = "field";
    private static final String VALIDATION_ERROR = "validation_error";

    private final EvidenceCampaignRepository repository;
    private final EvidenceCampaignRunRepository runRepository;
    private final ProjectService projectService;
    private final EvidenceArtifactService evidenceArtifactService;
    private final EvidenceCollectionAdapterRegistry adapterRegistry;
    private final ControlLinkService controlLinkService;
    private final ControlService controlService;
    private final EvidenceEndpointPolicy endpointPolicy;

    public EvidenceCampaignService(
            EvidenceCampaignRepository repository,
            EvidenceCampaignRunRepository runRepository,
            ProjectService projectService,
            EvidenceArtifactService evidenceArtifactService,
            EvidenceCollectionAdapterRegistry adapterRegistry,
            ControlLinkService controlLinkService,
            ControlService controlService,
            EvidenceEndpointPolicy endpointPolicy) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.projectService = projectService;
        this.evidenceArtifactService = evidenceArtifactService;
        this.adapterRegistry = adapterRegistry;
        this.controlLinkService = controlLinkService;
        this.controlService = controlService;
        this.endpointPolicy = endpointPolicy;
    }

    @Transactional
    public EvidenceCampaign create(CreateEvidenceCampaignCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException(
                    "EvidenceCampaign with UID '" + command.uid() + "' already exists in project "
                            + project.getIdentifier(),
                    "evidence_campaign_uid_conflict",
                    Map.of(FIELD, "uid", "uid", command.uid()));
        }
        endpointPolicy.validate(command.connectionEndpoint());
        validateTargetControls(project.getId(), command.targetControlIds());

        Instant firstRun = command.firstRunAt() != null ? command.firstRunAt() : Instant.now();
        var campaign = new EvidenceCampaign(
                project,
                command.uid(),
                command.name(),
                command.frequency(),
                command.adapterName(),
                command.scopeType(),
                command.connectionProfileId(),
                command.connectionEndpoint(),
                command.credentialRef(),
                firstRun);
        campaign.setSchemaId(command.schemaId());
        campaign.setScopeCriteria(command.scopeCriteria());
        campaign.setTargetControlIds(command.targetControlIds());
        campaign.setRetentionDays(command.retentionDays());

        var saved = repository.save(campaign);
        log.info(
                "evidence_campaign_created: project={} uid={} adapter={} frequency={} id={}",
                project.getIdentifier(),
                saved.getUid(),
                saved.getAdapterName(),
                saved.getFrequency(),
                saved.getId());
        return saved;
    }

    @Transactional
    public EvidenceCampaign update(UUID projectId, UUID id, UpdateEvidenceCampaignCommand command) {
        var campaign = findOrThrow(projectId, id);
        // Partial-update semantics: null means "leave unchanged". But a *present* blank value for a
        // required field would silently overwrite valid aggregate state with an invalid one (the
        // request DTO only length-bounds these strings), so reject blank-on-present before mutating.
        requirePresentValueNotBlank(command.name(), "name");
        requirePresentValueNotBlank(command.scopeType(), "scopeType");
        requirePresentValueNotBlank(command.connectionProfileId(), "connectionProfileId");
        requirePresentValueNotBlank(command.credentialRef(), "credentialRef");
        if (command.name() != null) {
            campaign.setName(command.name());
        }
        if (command.frequency() != null) {
            campaign.setFrequency(command.frequency());
        }
        if (command.scopeType() != null) {
            campaign.setScopeType(command.scopeType());
        }
        if (command.schemaId() != null) {
            campaign.setSchemaId(command.schemaId());
        }
        if (command.connectionProfileId() != null) {
            campaign.setConnectionProfileId(command.connectionProfileId());
        }
        if (command.connectionEndpoint() != null) {
            endpointPolicy.validate(command.connectionEndpoint());
            campaign.setConnectionEndpoint(command.connectionEndpoint());
        }
        if (command.credentialRef() != null) {
            campaign.setCredentialRef(command.credentialRef());
        }
        if (command.scopeCriteria() != null) {
            campaign.setScopeCriteria(command.scopeCriteria());
        }
        if (command.targetControlIds() != null) {
            validateTargetControls(projectId, command.targetControlIds());
            campaign.setTargetControlIds(command.targetControlIds());
        }
        if (command.retentionDays() != null) {
            campaign.setRetentionDays(command.retentionDays());
        }
        var saved = repository.save(campaign);
        log.info("evidence_campaign_updated: uid={} id={}", saved.getUid(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public EvidenceCampaign getById(UUID projectId, UUID id) {
        return findOrThrow(projectId, id);
    }

    @Transactional(readOnly = true)
    public List<EvidenceCampaign> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional
    public EvidenceCampaign pause(UUID projectId, UUID id) {
        var campaign = findOrThrow(projectId, id);
        campaign.setStatus(EvidenceCampaignStatus.PAUSED);
        var saved = repository.save(campaign);
        log.info("evidence_campaign_paused: uid={} id={}", saved.getUid(), saved.getId());
        return saved;
    }

    @Transactional
    public EvidenceCampaign resume(UUID projectId, UUID id) {
        var campaign = findOrThrow(projectId, id);
        campaign.setStatus(EvidenceCampaignStatus.ACTIVE);
        var saved = repository.save(campaign);
        log.info("evidence_campaign_resumed: uid={} id={}", saved.getUid(), saved.getId());
        return saved;
    }

    /**
     * Run a campaign immediately, irrespective of its schedule. The collection
     * window spans from the campaign's last run (or creation, if it has never
     * run) up to now.
     */
    public EvidenceCampaignRun trigger(UUID projectId, UUID id) {
        var campaign = findOrThrow(projectId, id);
        Instant now = Instant.now();
        Instant windowStart = campaign.getLastRunAt() != null ? campaign.getLastRunAt() : campaign.getCreatedAt();
        if (windowStart == null || windowStart.isAfter(now)) {
            windowStart = now;
        }
        campaign.setLastRunAt(now);
        repository.save(campaign);
        return executeCampaign(campaign, windowStart, now);
    }

    @Transactional(readOnly = true)
    public List<EvidenceCampaignRun> listRuns(UUID projectId, UUID id) {
        findOrThrow(projectId, id);
        return runRepository.findByCampaignIdAndProjectIdOrderByWindowStartDesc(id, projectId);
    }

    /**
     * Claim and execute every ACTIVE campaign whose {@code nextRunAt} is at or
     * before {@code now}. Each campaign is claimed with an optimistic
     * conditional advance of its scheduling cursor; a campaign lost to a
     * concurrent sweep is skipped. Returns the number of campaigns executed.
     */
    public int runDueCampaigns(Instant now) {
        var due = repository.findByStatusAndNextRunAtLessThanEqual(EvidenceCampaignStatus.ACTIVE, now);
        int executed = 0;
        for (var campaign : due) {
            Instant observed = campaign.getNextRunAt();
            // Advance the cursor past `now` so a campaign that has been due for
            // several periods (paused engine, downtime, long retention gap)
            // coalesces into a single [observed, now] run rather than emitting
            // one overlapping window per missed period. Each step is at least
            // one frequency interval, so this terminates.
            Instant next = campaign.getFrequency().advance(observed);
            while (!next.isAfter(now)) {
                next = campaign.getFrequency().advance(next);
            }
            // The claim is conditional on the campaign still being ACTIVE, so a pause that lands
            // between the due-select and this update makes the claim a no-op and the paused campaign
            // is skipped rather than executed.
            int claimed =
                    repository.markClaimedIfDue(campaign.getId(), observed, next, now, EvidenceCampaignStatus.ACTIVE);
            if (claimed == 0) {
                log.info("evidence_campaign_claim_lost: uid={} id={}", campaign.getUid(), campaign.getId());
                continue;
            }
            executeCampaign(campaign, observed, now);
            executed++;
        }
        return executed;
    }

    /**
     * Execute one collection window for {@code campaign}: invoke its adapter,
     * persist collected results as evidence artifacts, link them to the target
     * controls, and record an {@link EvidenceCampaignRun}. Adapter failures are
     * captured on the run as {@code FAILED} rather than propagated, so one
     * campaign's failure does not abort a sweep.
     */
    public EvidenceCampaignRun executeCampaign(EvidenceCampaign campaign, Instant windowStart, Instant windowEnd) {
        var run = new EvidenceCampaignRun(
                campaign, campaign.getProject(), EvidenceCampaignRunStatus.RUNNING, windowStart, windowEnd);
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);

        try {
            EvidenceCollectionAdapter adapter = adapterRegistry.getAdapter(campaign.getAdapterName());
            EvidenceCollectionResult result = adapter.collect(buildRequest(campaign, windowStart, windowEnd));

            var persisted = persistCollectedArtifacts(campaign, result.artifacts());
            int totalErrors = result.errors().size() + persisted.persistErrors();
            run.setProducedArtifactIds(persisted.producedIds());
            run.setArtifactCount(persisted.producedIds().size());
            run.setErrorCount(totalErrors);
            run.setSanitizedError(errorSummary(result.errors(), persisted.persistErrors()));
            run.setStatus(mapStatus(result.status(), totalErrors));
        } catch (RuntimeException ex) {
            run.setArtifactCount(
                    run.getProducedArtifactIds() == null
                            ? 0
                            : run.getProducedArtifactIds().size());
            run.setErrorCount(Math.max(1, run.getErrorCount()));
            // Store only the controlled exception category. The raw exception message can echo the
            // endpoint URI (with userinfo), provider response text, or PII, and the run is readable by
            // any project member - so raw exception detail is kept out of the persisted summary.
            run.setSanitizedError("run_error: " + ex.getClass().getSimpleName());
            run.setStatus(EvidenceCampaignRunStatus.FAILED);
            log.warn(
                    "evidence_campaign_run_failed: uid={} id={} error={}",
                    campaign.getUid(),
                    campaign.getId(),
                    ex.getClass().getSimpleName());
        }
        run.setFinishedAt(Instant.now());
        var saved = runRepository.save(run);
        log.info(
                "evidence_campaign_run_finished: uid={} runId={} status={} artifacts={} errors={}",
                campaign.getUid(),
                saved.getId(),
                saved.getStatus(),
                saved.getArtifactCount(),
                saved.getErrorCount());
        return saved;
    }

    /**
     * Persist each collected artifact and link it to the campaign's target controls. A single
     * artifact failing to persist (e.g. a re-collected UID already present, ADR-045 append-only)
     * must not abort the whole collection - it is recorded as a persist error and the rest proceed.
     */
    private PersistOutcome persistCollectedArtifacts(
            EvidenceCampaign campaign, List<CreateEvidenceArtifactCommand> commands) {
        var producedIds = new ArrayList<UUID>(commands.size());
        int persistErrors = 0;
        for (var artifactCommand : commands) {
            try {
                var artifact = evidenceArtifactService.create(artifactCommand);
                producedIds.add(artifact.getId());
                linkToControls(campaign, artifact.getId());
            } catch (RuntimeException ex) {
                persistErrors++;
                log.warn(
                        "evidence_campaign_artifact_persist_failed: uid={} error={}",
                        campaign.getUid(),
                        ex.getClass().getSimpleName());
            }
        }
        return new PersistOutcome(producedIds, persistErrors);
    }

    private record PersistOutcome(List<UUID> producedIds, int persistErrors) {}

    /**
     * Delete finished runs older than each campaign's retention horizon.
     * Campaigns with no {@code retentionDays} retain runs indefinitely. Returns
     * the total number of runs pruned.
     */
    public int pruneExpiredRuns(Instant now) {
        int pruned = 0;
        for (var campaign : repository.findAll()) {
            Integer days = campaign.getRetentionDays();
            if (days == null || days <= 0) {
                continue;
            }
            Instant cutoff = now.minus(Duration.ofDays(days));
            pruned += runRepository.deleteFinishedRunsBefore(campaign.getId(), cutoff);
        }
        if (pruned > 0) {
            log.info("evidence_campaign_runs_pruned: count={}", pruned);
        }
        return pruned;
    }

    private EvidenceCollectionRequest buildRequest(EvidenceCampaign campaign, Instant windowStart, Instant windowEnd) {
        // Re-validate and resolve at the moment of use (not only at store time): a hostname that
        // rebound to an internal address after create/update is rejected here (the throw is captured
        // by executeCampaign and the run is marked FAILED). The validated addresses are pinned into
        // the connection so a conforming adapter connects to them instead of re-resolving the host,
        // closing the residual DNS-rebinding window the store-time check cannot.
        List<String> pinnedAddresses = endpointPolicy.validateAndResolve(campaign.getConnectionEndpoint()).stream()
                .map(InetAddress::getHostAddress)
                .toList();
        var connection = new EvidenceConnectionConfig(
                campaign.getConnectionProfileId(),
                URI.create(campaign.getConnectionEndpoint()),
                campaign.getCredentialRef(),
                Map.of(EvidenceConnectionConfig.PINNED_ADDRESSES_SETTING, pinnedAddresses));
        var scope = new EvidenceCollectionScope(
                campaign.getScopeType(),
                campaign.getScopeCriteria() == null ? Map.of() : campaign.getScopeCriteria(),
                windowStart,
                windowEnd,
                null);
        // Forward the campaign's configured output schema so the adapter collects to the right evidence
        // schema/type; the port carries it in options (no typed schema-selection field). Omitted when unset.
        Map<String, Object> options =
                campaign.getSchemaId() == null || campaign.getSchemaId().isBlank()
                        ? Map.of()
                        : Map.of(EvidenceCollectionRequest.SCHEMA_OPTION, campaign.getSchemaId());
        return new EvidenceCollectionRequest(campaign.getProject().getId(), connection, scope, null, options);
    }

    private void linkToControls(EvidenceCampaign campaign, UUID artifactId) {
        var controlIds = campaign.getTargetControlIds();
        if (controlIds == null || controlIds.isEmpty()) {
            return;
        }
        Project project = campaign.getProject();
        for (UUID controlId : controlIds) {
            try {
                controlLinkService.create(
                        project.getId(),
                        controlId,
                        new CreateControlLinkCommand(
                                ControlLinkTargetType.EVIDENCE,
                                artifactId,
                                null,
                                ControlLinkType.EVIDENCED_BY,
                                null,
                                null));
            } catch (ConflictException duplicate) {
                // Link already exists for this control/artifact pair; linking is idempotent.
                log.debug("evidence_campaign_control_link_exists: control={} artifact={}", controlId, artifactId);
            }
        }
    }

    private static EvidenceCampaignRunStatus mapStatus(EvidenceCollectionStatus status, int totalErrors) {
        return switch (status) {
            case SUCCEEDED -> totalErrors == 0
                    ? EvidenceCampaignRunStatus.COMPLETED
                    : EvidenceCampaignRunStatus.PARTIAL;
            case PARTIAL, RATE_LIMITED -> EvidenceCampaignRunStatus.PARTIAL;
            case FAILED -> EvidenceCampaignRunStatus.FAILED;
        };
    }

    private static String errorSummary(List<EvidenceCollectionError> adapterErrors, int persistErrors) {
        if (adapterErrors != null && !adapterErrors.isEmpty()) {
            var first = adapterErrors.get(0);
            // Lead with the adapter's controlled error code; the provider-supplied message is redacted
            // and length-bounded before it is persisted/returned.
            String redacted = EvidenceRunErrorRedactor.redact(first.message());
            return redacted == null ? first.errorCode() : first.errorCode() + ": " + redacted;
        }
        if (persistErrors > 0) {
            return "persist_error: " + persistErrors + " artifact(s) failed to persist";
        }
        return null;
    }

    private static void requirePresentValueNotBlank(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new DomainValidationException(
                    field + " must not be blank when provided", VALIDATION_ERROR, Map.of(FIELD, field));
        }
    }

    /**
     * Fail fast at create/update time if any target control does not resolve in
     * the campaign's project, so run-time artifact linking cannot abort a
     * collection on a misconfigured or cross-project control reference.
     */
    private void validateTargetControls(UUID projectId, List<UUID> controlIds) {
        if (controlIds == null) {
            return;
        }
        for (UUID controlId : controlIds) {
            controlService.getById(projectId, controlId);
        }
    }

    private EvidenceCampaign findOrThrow(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("EvidenceCampaign not found: " + id));
    }
}
