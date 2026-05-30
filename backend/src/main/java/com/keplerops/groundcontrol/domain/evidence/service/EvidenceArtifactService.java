package com.keplerops.groundcontrol.domain.evidence.service;

import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.verification.repository.VerificationResultRepository;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for {@link EvidenceArtifact} per GC-M016 / ADR-045.
 *
 * <p>Owns project-scoped create / read / supersede. There is no update or
 * delete path — the controller exposes neither PUT nor DELETE — so the
 * artifact set is append-only at the API boundary. The single permitted
 * post-create write is {@code setSupersededByArtifactId}, exercised exactly
 * once via {@link #supersede(UUID, UUID, CreateEvidenceArtifactCommand)} when
 * a newer artifact replaces a prior one.
 *
 * <p>Source-reference validation runs in this layer: internal kinds resolve
 * to a project-scoped first-class entity via the corresponding repository's
 * existence check; external kinds require only a non-blank
 * {@code sourceIdentifier}. Cross-project source references fail closed.
 */
@Service
@Transactional
public class EvidenceArtifactService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceArtifactService.class);
    private static final String VALIDATION_ERROR = "validation_error";
    private static final String FIELD = "field";
    private static final String SOURCES = "sources";
    private static final String SOURCE_INDEX = "sourceIndex";
    private static final String SOURCE_KIND_SUFFIX = "].sourceKind";
    private static final String SOURCE_ENTITY_ID_SUFFIX = "].sourceEntityId";
    private static final String SOURCE_IDENTIFIER_SUFFIX = "].sourceIdentifier";

    private static String sourcesFieldPath(int index, String suffix) {
        return "sources[" + index + suffix;
    }

    private final EvidenceArtifactRepository repository;
    private final ProjectService projectService;
    private final ObservationRepository observationRepository;
    private final ControlTestRepository controlTestRepository;
    private final ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository;
    private final VerificationResultRepository verificationResultRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final FindingRepository findingRepository;

    public EvidenceArtifactService(
            EvidenceArtifactRepository repository,
            ProjectService projectService,
            ObservationRepository observationRepository,
            ControlTestRepository controlTestRepository,
            ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository,
            VerificationResultRepository verificationResultRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            FindingRepository findingRepository) {
        this.repository = repository;
        this.projectService = projectService;
        this.observationRepository = observationRepository;
        this.controlTestRepository = controlTestRepository;
        this.controlEffectivenessAssessmentRepository = controlEffectivenessAssessmentRepository;
        this.verificationResultRepository = verificationResultRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.findingRepository = findingRepository;
    }

    public EvidenceArtifact create(CreateEvidenceArtifactCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException(
                    "EvidenceArtifact with UID '" + command.uid() + "' already exists in project "
                            + project.getIdentifier(),
                    "evidence_artifact_uid_conflict",
                    Map.of(FIELD, "uid", "uid", command.uid()));
        }
        var validatedSources = validateSources(project.getId(), command.sources());

        var artifact = new EvidenceArtifact(
                project,
                command.uid(),
                command.title(),
                command.summary(),
                command.evidenceType(),
                command.derivationMethod(),
                command.derivedAt());
        artifact.setAssuranceLevel(command.assuranceLevel());
        artifact.setConfidence(command.confidence());
        artifact.setNotes(command.notes());
        artifact.setSources(validatedSources);
        artifact.setDerivedBy(ActorHolder.get());
        var validityDays = command.validityWindowDays();
        if (validityDays != null && validityDays <= 0) {
            throw new DomainValidationException(
                    "validityWindowDays must be positive when provided",
                    VALIDATION_ERROR,
                    Map.of(FIELD, "validityWindowDays"));
        }
        if (command.expiresAt() != null && command.expiresAt().isBefore(command.derivedAt())) {
            throw new DomainValidationException(
                    "expiresAt must not be before derivedAt", VALIDATION_ERROR, Map.of(FIELD, "expiresAt"));
        }
        artifact.setExpiresAt(command.expiresAt());
        artifact.setValidityWindowDays(validityDays);

        var saved = repository.save(artifact);
        log.info(
                "evidence_artifact_created: project={} uid={} type={} sources={} id={}",
                project.getIdentifier(),
                saved.getUid(),
                saved.getEvidenceType(),
                validatedSources.size(),
                saved.getId());
        return saved;
    }

    /**
     * Create a new artifact that supersedes the prior one identified by
     * {@code priorId}, then write the prior row's {@code
     * supersededByArtifactId} exactly once. The prior artifact's other fields
     * are not modified; Envers records the single-field revision.
     */
    public EvidenceArtifact supersede(UUID projectId, UUID priorId, CreateEvidenceArtifactCommand command) {
        var prior = findOrThrow(projectId, priorId);
        if (prior.getSupersededByArtifactId() != null) {
            throw alreadySupersededConflict(prior);
        }
        if (projectId != null && !projectId.equals(command.projectId())) {
            throw new DomainValidationException(
                    "supersede command projectId must match the prior artifact's project",
                    VALIDATION_ERROR,
                    Map.of(FIELD, "projectId"));
        }
        var replacement = create(command);
        // Conditional update enforces the supersede-once invariant under concurrent writers (ADR-045).
        int updated = repository.markSupersededIfUnset(prior.getId(), projectId, replacement.getId());
        if (updated == 0) {
            // Re-read so the conflict envelope reflects the winning replacement, not the stale in-memory copy.
            var refreshed = findOrThrow(projectId, priorId);
            throw alreadySupersededConflict(refreshed);
        }
        log.info(
                "evidence_artifact_superseded: priorId={} priorUid={} replacementId={} replacementUid={}",
                prior.getId(),
                prior.getUid(),
                replacement.getId(),
                replacement.getUid());
        return replacement;
    }

    private static ConflictException alreadySupersededConflict(EvidenceArtifact prior) {
        Map<String, Serializable> detail = new LinkedHashMap<>();
        detail.put("priorUid", prior.getUid());
        if (prior.getSupersededByArtifactId() != null) {
            detail.put("priorSupersededBy", prior.getSupersededByArtifactId().toString());
        }
        return new ConflictException(
                "EvidenceArtifact " + prior.getUid() + " is already superseded",
                "evidence_artifact_already_superseded",
                detail);
    }

    @Transactional(readOnly = true)
    public EvidenceArtifact getById(UUID projectId, UUID id) {
        return findOrThrow(projectId, id);
    }

    @Transactional(readOnly = true)
    public List<EvidenceArtifact> listByProject(UUID projectId, EvidenceType evidenceType, boolean includeSuperseded) {
        var rows = evidenceType == null
                ? repository.findByProjectIdOrderByDerivedAtDesc(projectId)
                : repository.findByProjectIdAndEvidenceTypeOrderByDerivedAtDesc(projectId, evidenceType);
        if (includeSuperseded) {
            return rows;
        }
        return rows.stream().filter(a -> a.getSupersededByArtifactId() == null).toList();
    }

    /**
     * GC-I004 expiry filter. Returns the rows matching the same filters as
     * {@link #listByProject(UUID, EvidenceType, boolean)} additionally
     * filtered by {@code expired}: when {@code expired == true} only rows
     * with a non-null {@code expiresAt} that is &lt;= {@code asOf}; when
     * {@code expired == false} only rows whose current state is NOT expired
     * (no {@code expiresAt}, or {@code expiresAt} is in the future).
     */
    @Transactional(readOnly = true)
    public List<EvidenceArtifact> listByProjectFilteredByExpiry(
            UUID projectId,
            EvidenceType evidenceType,
            boolean includeSuperseded,
            java.time.Instant asOf,
            boolean expired) {
        return listByProject(projectId, evidenceType, includeSuperseded).stream()
                .filter(a -> a.isExpiredAt(asOf) == expired)
                .toList();
    }

    private EvidenceArtifact findOrThrow(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("EvidenceArtifact not found: " + id));
    }

    private List<EvidenceSourceRef> validateSources(UUID projectId, List<EvidenceSourceRef> input) {
        if (input == null || input.isEmpty()) {
            throw new DomainValidationException(
                    "EvidenceArtifact must have at least one source", VALIDATION_ERROR, Map.of(FIELD, SOURCES));
        }
        var out = new ArrayList<EvidenceSourceRef>(input.size());
        for (int i = 0; i < input.size(); i++) {
            var ref = input.get(i);
            if (ref == null) {
                throw new DomainValidationException(
                        "sources must not contain null elements",
                        VALIDATION_ERROR,
                        Map.of(FIELD, SOURCES, SOURCE_INDEX, i));
            }
            if (ref.sourceKind() == null) {
                var path = sourcesFieldPath(i, SOURCE_KIND_SUFFIX);
                throw new DomainValidationException(
                        path + " must not be null", VALIDATION_ERROR, Map.of(FIELD, path, SOURCE_INDEX, i));
            }
            validateOneSource(projectId, i, ref);
            out.add(ref);
        }
        return Collections.unmodifiableList(out);
    }

    private void validateOneSource(UUID projectId, int index, EvidenceSourceRef ref) {
        var kind = ref.sourceKind();
        var entityId = ref.sourceEntityId();
        var identifier = ref.sourceIdentifier();
        var entityIdPath = sourcesFieldPath(index, SOURCE_ENTITY_ID_SUFFIX);
        var identifierPath = sourcesFieldPath(index, SOURCE_IDENTIFIER_SUFFIX);
        if (kind.isInternal()) {
            if (entityId == null) {
                throw new DomainValidationException(
                        entityIdPath + " is required for internal source kind " + kind,
                        VALIDATION_ERROR,
                        Map.of(FIELD, entityIdPath, SOURCE_INDEX, index));
            }
            if (identifier != null) {
                throw new DomainValidationException(
                        identifierPath + " must be null for internal source kind " + kind,
                        VALIDATION_ERROR,
                        Map.of(FIELD, identifierPath, SOURCE_INDEX, index));
            }
            if (!internalSourceExists(projectId, kind, entityId)) {
                Map<String, Serializable> detail = new LinkedHashMap<>();
                detail.put(FIELD, entityIdPath);
                detail.put(SOURCE_INDEX, index);
                detail.put("sourceKind", kind.name());
                detail.put("sourceEntityId", entityId.toString());
                // Validation failure (the caller's UUID does not resolve in this project),
                // not a 404 on the artifact itself.
                throw new DomainValidationException(
                        "EvidenceSourceRef target not found in this project: " + kind + "/" + entityId,
                        "evidence_source_target_not_found",
                        detail);
            }
        } else {
            if (entityId != null) {
                throw new DomainValidationException(
                        entityIdPath + " must be null for external source kind " + kind,
                        VALIDATION_ERROR,
                        Map.of(FIELD, entityIdPath, SOURCE_INDEX, index));
            }
            if (identifier == null || identifier.isBlank()) {
                throw new DomainValidationException(
                        identifierPath + " is required for external source kind " + kind,
                        VALIDATION_ERROR,
                        Map.of(FIELD, identifierPath, SOURCE_INDEX, index));
            }
        }
    }

    private boolean internalSourceExists(UUID projectId, EvidenceSourceKind kind, UUID entityId) {
        return switch (kind) {
            case OBSERVATION -> observationRepository.existsByIdAndProjectId(entityId, projectId);
            case CONTROL_TEST -> controlTestRepository
                    .findByIdAndProjectId(entityId, projectId)
                    .isPresent();
            case CONTROL_EFFECTIVENESS_ASSESSMENT -> controlEffectivenessAssessmentRepository
                    .findByIdAndProjectId(entityId, projectId)
                    .isPresent();
            case VERIFICATION_RESULT -> verificationResultRepository.existsByIdAndProjectId(entityId, projectId);
            case RISK_ASSESSMENT_RESULT -> riskAssessmentResultRepository.existsByIdAndProjectId(entityId, projectId);
            case FINDING -> findingRepository.existsByIdAndProjectId(entityId, projectId);
            case ATTESTATION, EXTERNAL, CI_PIPELINE_RESULT, SECURITY_SCAN_RESULT -> false;
        };
    }
}
