package com.keplerops.groundcontrol.domain.threatenumeration.service;

import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredThreatRule;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityVerifier;
import com.keplerops.groundcontrol.domain.packregistry.service.PackResolver;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatEnumerationLimitationReason;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic STRIDE rule-pack threat-enumeration engine over a persisted architecture-model
 * snapshot (GC-GRC-007). No LLM involvement: given the same pack definition and element views the
 * result is byte-stable. Mirrors the {@code DataClassificationEvaluationService} sibling pattern:
 * a pure static {@link #enumerate} method exercised by unit tests, and read-only service wrappers
 * that resolve the pack definition and snapshot before delegating to it.
 */
@Service
public class ThreatEnumerationService {

    public static final String SCHEMA_VERSION = "threat-enumeration/v1";

    private static final Logger log = LoggerFactory.getLogger(ThreatEnumerationService.class);

    private final PackResolver packResolver;
    private final PackIntegrityVerifier packIntegrityVerifier;
    private final ArchitectureModelSnapshotRepository snapshotRepository;
    private final ArchitectureModelElementStateRepository stateRepository;

    public ThreatEnumerationService(
            PackResolver packResolver,
            PackIntegrityVerifier packIntegrityVerifier,
            ArchitectureModelSnapshotRepository snapshotRepository,
            ArchitectureModelElementStateRepository stateRepository) {
        this.packResolver = packResolver;
        this.packIntegrityVerifier = packIntegrityVerifier;
        this.snapshotRepository = snapshotRepository;
        this.stateRepository = stateRepository;
    }

    /**
     * Enumerate threats against the project's most recent architecture-model snapshot. Throws
     * {@link NotFoundException} when no THREAT_RULE_PACK matching {@code packId} /
     * {@code versionConstraint} is available for this project.
     */
    @Transactional(readOnly = true)
    public ThreatEnumerationResult enumerateLatest(UUID projectId, String packId, String versionConstraint) {
        var definition = resolvePackDefinition(projectId, packId, versionConstraint);
        var snapshot = snapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .findFirst();
        if (snapshot.isEmpty()) {
            var result = new ThreatEnumerationResult(
                    SCHEMA_VERSION,
                    definition.packId(),
                    definition.resolvedVersion(),
                    definition.checksum(),
                    null,
                    null,
                    List.of(),
                    List.of(new ThreatEnumerationLimitation(
                            ThreatEnumerationLimitationReason.NO_SNAPSHOT,
                            "No architecture-model snapshot found for project",
                            null)));
            log.info(
                    "threat_enumeration: project={} pack={}@{} snapshot=none candidates=0 limitations=1",
                    projectId,
                    packId,
                    definition.resolvedVersion());
            return result;
        }
        var snap = snapshot.get();
        var views = stateRepository.findBySnapshotIdOrderByStableKey(snap.getId()).stream()
                .map(ThreatCandidateElementView::from)
                .toList();
        var result = enumerate(definition, snap.getId().toString(), snap.getModelVersion(), views);
        log.info(
                "threat_enumeration: project={} pack={}@{} snapshot={} candidates={} limitations={}",
                projectId,
                packId,
                definition.resolvedVersion(),
                snap.getId(),
                result.candidates().size(),
                result.limitations().size());
        return result;
    }

    /**
     * Enumerate threats against a specific architecture-model snapshot, scoped to the project.
     * Throws {@link NotFoundException} when the snapshot or pack is not found.
     */
    @Transactional(readOnly = true)
    public ThreatEnumerationResult enumerateSnapshot(
            UUID projectId, UUID snapshotId, String packId, String versionConstraint) {
        var definition = resolvePackDefinition(projectId, packId, versionConstraint);
        var snap = snapshotRepository
                .findByIdAndProjectId(snapshotId, projectId)
                .orElseThrow(() -> new NotFoundException("Architecture model snapshot not found: " + snapshotId));
        var views = stateRepository.findBySnapshotIdOrderByStableKey(snap.getId()).stream()
                .map(ThreatCandidateElementView::from)
                .toList();
        var result = enumerate(definition, snap.getId().toString(), snap.getModelVersion(), views);
        log.info(
                "threat_enumeration: project={} pack={}@{} snapshot={} candidates={} limitations={}",
                projectId,
                packId,
                definition.resolvedVersion(),
                snap.getId(),
                result.candidates().size(),
                result.limitations().size());
        return result;
    }

    /**
     * Resolve the pack definition for a project from the pack registry. Throws
     * {@link NotFoundException} when no THREAT_RULE_PACK matching {@code packId} /
     * {@code versionConstraint} is registered or available for this project.
     */
    @Transactional(readOnly = true)
    public ThreatRulePackDefinition resolvePackDefinition(UUID projectId, String packId, String versionConstraint) {
        var resolved = packResolver.resolve(projectId, packId, versionConstraint);
        var verification = packIntegrityVerifier.verify(resolved);
        var entry = resolved.entry();
        var entries = entry.getThreatRuleEntries();
        if (entries == null || entries.isEmpty()) {
            throw new NotFoundException(
                    "Resolved pack '" + packId + "@" + resolved.resolvedVersion() + "' has no threat rule entries");
        }
        var rules = entries.stream().map(ThreatEnumerationService::toThreatRule).toList();
        return new ThreatRulePackDefinition(packId, resolved.resolvedVersion(), verification.verifiedChecksum(), rules);
    }

    /**
     * Pure threat enumeration over a set of element views against a rule-pack definition.
     * Deterministic and side-effect free: the same definition and views always produce the same
     * ordered candidate list (sorted by {@code elementStableKey, ruleId, strideCategory}).
     */
    public static ThreatEnumerationResult enumerate(
            ThreatRulePackDefinition definition,
            String snapshotId,
            String modelVersion,
            List<ThreatCandidateElementView> views) {

        // Index endpoints by stable key, but ONLY for views the engine considers valid (non-blank
        // stable key). Elements skipped by the MISSING_STABLE_KEY guard below must not be
        // addressable as flow endpoints: otherwise a malformed snapshot with a blank-key element
        // could make SOURCE_IS_EXTERNAL / TARGET_IS_EXTERNAL / CROSSES_TRUST_BOUNDARY resolve a flow
        // endpoint to an excluded element and emit a false deterministic candidate instead of a
        // dangling-endpoint limitation. On a derivation-backed GRC engine, a false candidate is
        // worse than dropped coverage.
        Map<String, ThreatCandidateElementView> byKey = new LinkedHashMap<>();
        for (var view : views) {
            var key = view.stableKey();
            if (key != null && !key.isBlank()) {
                byKey.putIfAbsent(key, view);
            }
        }

        List<ThreatCandidate> candidates = new ArrayList<>();
        List<ThreatEnumerationLimitation> limitations = new ArrayList<>();

        for (var view : views) {
            if (view.stableKey() == null || view.stableKey().isBlank()) {
                limitations.add(new ThreatEnumerationLimitation(
                        ThreatEnumerationLimitationReason.MISSING_STABLE_KEY,
                        "Element has a blank or null stable key and was skipped",
                        null));
                continue;
            }

            for (var rule : definition.rules()) {
                if (!rule.targetElementKinds().contains(view.elementKind())) {
                    continue;
                }
                var match = evaluatePredicate(rule, view, byKey, limitations);
                if (match != null) {
                    var narrative = buildNarrative(rule, view, match);
                    candidates.add(new ThreatCandidate(
                            rule.ruleId(),
                            rule.category(),
                            rule.strideCategory(),
                            view.stableKey(),
                            view.elementKind(),
                            match,
                            narrative));
                }
            }
        }

        // Deterministic sort: same definition + views → identical ordered candidate list
        candidates.sort(Comparator.comparing(ThreatCandidate::elementStableKey)
                .thenComparing(ThreatCandidate::producingRuleId)
                .thenComparing(c -> c.strideCategory().name()));

        return new ThreatEnumerationResult(
                SCHEMA_VERSION,
                definition.packId(),
                definition.resolvedVersion(),
                definition.checksum(),
                snapshotId,
                modelVersion,
                candidates,
                limitations);
    }

    /**
     * Evaluate the rule's predicate against the view. Returns a bounded matchedFacts map when the
     * predicate matches, null when it does not. Records a DANGLING_FLOW_ENDPOINT limitation when
     * a DATA_FLOW predicate needs an endpoint that is absent from byKey.
     */
    private static Map<String, String> evaluatePredicate(
            com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatRule rule,
            ThreatCandidateElementView view,
            Map<String, ThreatCandidateElementView> byKey,
            List<ThreatEnumerationLimitation> limitations) {

        return switch (rule.predicate()) {
            case ALWAYS -> {
                var facts = new LinkedHashMap<String, String>();
                facts.put("elementKind", view.elementKind().name());
                facts.put("predicate", ThreatRuleMatchPredicate.ALWAYS.name());
                yield facts;
            }
            case CROSSES_TRUST_BOUNDARY -> {
                if (view.elementKind() != ArchitectureModelElementKind.DATA_FLOW) {
                    yield null;
                }
                var sourceKey = view.flowSourceStableKey();
                var targetKey = view.flowTargetStableKey();
                var source = byKey.get(sourceKey);
                var target = byKey.get(targetKey);
                if (source == null || target == null) {
                    if (sourceKey != null && target == null && byKey.containsKey(sourceKey)) {
                        limitations.add(new ThreatEnumerationLimitation(
                                ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT,
                                "Flow target endpoint not present in snapshot for CROSSES_TRUST_BOUNDARY predicate",
                                view.stableKey()));
                    } else if (targetKey != null && source == null && byKey.containsKey(targetKey)) {
                        limitations.add(new ThreatEnumerationLimitation(
                                ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT,
                                "Flow source endpoint not present in snapshot for CROSSES_TRUST_BOUNDARY predicate",
                                view.stableKey()));
                    } else if (source == null || target == null) {
                        limitations.add(new ThreatEnumerationLimitation(
                                ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT,
                                "Flow endpoint(s) not present in snapshot for CROSSES_TRUST_BOUNDARY predicate",
                                view.stableKey()));
                    }
                    yield null;
                }
                // Match if source and target have different (non-null) trust boundary keys,
                // or if the flow itself has a non-blank trustBoundaryKey
                var sourceTb = trimToNull(source.trustBoundaryKey());
                var targetTb = trimToNull(target.trustBoundaryKey());
                boolean crossesBoundary = (sourceTb != null && targetTb != null && !sourceTb.equals(targetTb))
                        || trimToNull(view.trustBoundaryKey()) != null;
                if (!crossesBoundary) {
                    yield null;
                }
                var facts = new LinkedHashMap<String, String>();
                facts.put("elementKind", view.elementKind().name());
                facts.put("predicate", ThreatRuleMatchPredicate.CROSSES_TRUST_BOUNDARY.name());
                if (sourceTb != null) facts.put("sourceTrustBoundaryKey", sourceTb);
                if (targetTb != null) facts.put("targetTrustBoundaryKey", targetTb);
                if (trimToNull(view.trustBoundaryKey()) != null) facts.put("trustBoundaryKey", view.trustBoundaryKey());
                yield facts;
            }
            case SOURCE_IS_EXTERNAL -> {
                if (view.elementKind() != ArchitectureModelElementKind.DATA_FLOW) {
                    yield null;
                }
                var source = byKey.get(view.flowSourceStableKey());
                if (source == null) {
                    limitations.add(new ThreatEnumerationLimitation(
                            ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT,
                            "Flow source endpoint not present in snapshot for SOURCE_IS_EXTERNAL predicate",
                            view.stableKey()));
                    yield null;
                }
                if (source.elementKind() != ArchitectureModelElementKind.EXTERNAL_ENTITY) {
                    yield null;
                }
                var facts = new LinkedHashMap<String, String>();
                facts.put("elementKind", view.elementKind().name());
                facts.put("predicate", ThreatRuleMatchPredicate.SOURCE_IS_EXTERNAL.name());
                facts.put("sourceStableKey", source.stableKey());
                facts.put("sourceElementKind", source.elementKind().name());
                yield facts;
            }
            case TARGET_IS_EXTERNAL -> {
                if (view.elementKind() != ArchitectureModelElementKind.DATA_FLOW) {
                    yield null;
                }
                var target = byKey.get(view.flowTargetStableKey());
                if (target == null) {
                    limitations.add(new ThreatEnumerationLimitation(
                            ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT,
                            "Flow target endpoint not present in snapshot for TARGET_IS_EXTERNAL predicate",
                            view.stableKey()));
                    yield null;
                }
                if (target.elementKind() != ArchitectureModelElementKind.EXTERNAL_ENTITY) {
                    yield null;
                }
                var facts = new LinkedHashMap<String, String>();
                facts.put("elementKind", view.elementKind().name());
                facts.put("predicate", ThreatRuleMatchPredicate.TARGET_IS_EXTERNAL.name());
                facts.put("targetStableKey", target.stableKey());
                facts.put("targetElementKind", target.elementKind().name());
                yield facts;
            }
            case HAS_DATA_CLASSIFICATION -> {
                var dcKey = trimToNull(view.dataClassificationKey());
                if (dcKey == null) {
                    yield null;
                }
                var facts = new LinkedHashMap<String, String>();
                facts.put("elementKind", view.elementKind().name());
                facts.put("predicate", ThreatRuleMatchPredicate.HAS_DATA_CLASSIFICATION.name());
                facts.put("dataClassificationKey", dcKey);
                yield facts;
            }
            case HAS_TRUST_BOUNDARY -> {
                var tbKey = trimToNull(view.trustBoundaryKey());
                if (tbKey == null) {
                    yield null;
                }
                var facts = new LinkedHashMap<String, String>();
                facts.put("elementKind", view.elementKind().name());
                facts.put("predicate", ThreatRuleMatchPredicate.HAS_TRUST_BOUNDARY.name());
                facts.put("trustBoundaryKey", tbKey);
                yield facts;
            }
            case HAS_METADATA_TAG -> {
                var tagKey = rule.metadataTagKey();
                if (!view.metadata().containsKey(tagKey)) {
                    yield null;
                }
                var facts = new LinkedHashMap<String, String>();
                facts.put("elementKind", view.elementKind().name());
                facts.put("predicate", ThreatRuleMatchPredicate.HAS_METADATA_TAG.name());
                facts.put("metadataTagKey", tagKey);
                yield facts;
            }
        };
    }

    private static String buildNarrative(
            com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatRule rule,
            ThreatCandidateElementView view,
            Map<String, String> matchedFacts) {
        if (rule.narrativeSkeleton() == null || rule.narrativeSkeleton().isBlank()) {
            return null;
        }
        return rule.narrativeSkeleton()
                .replace("{{element}}", view.stableKey())
                .replace("{{elementKind}}", view.elementKind().name())
                .replace("{{strideCategory}}", rule.strideCategory().name());
    }

    private static ThreatRule toThreatRule(RegisteredThreatRule r) {
        return new ThreatRule(
                r.ruleId(),
                r.title(),
                r.category(),
                r.strideCategory(),
                r.targetElementKinds(),
                r.predicate(),
                r.metadataTagKey(),
                r.narrativeSkeleton(),
                r.rationale());
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
