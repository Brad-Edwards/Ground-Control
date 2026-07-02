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
import java.util.Optional;
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

    private static final String FACT_ELEMENT_KIND = "elementKind";
    private static final String FACT_PREDICATE = "predicate";

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
        var definition = doResolvePackDefinition(projectId, packId, versionConstraint);
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
        var definition = doResolvePackDefinition(projectId, packId, versionConstraint);
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
        return doResolvePackDefinition(projectId, packId, versionConstraint);
    }

    /**
     * Resolve the pack definition without opening its own transaction. The public
     * {@link #resolvePackDefinition} wrapper carries the {@code @Transactional} boundary for external
     * callers; the {@code enumerate*} methods already run inside a read-only transaction and call
     * this directly so they do not self-invoke a proxied transactional method (Sonar S6809).
     */
    private ThreatRulePackDefinition doResolvePackDefinition(UUID projectId, String packId, String versionConstraint) {
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
            collectCandidatesForView(definition, view, byKey, candidates, limitations);
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

    /** Match every rule whose target kind and predicate fire for a single (valid-key) view. */
    private static void collectCandidatesForView(
            ThreatRulePackDefinition definition,
            ThreatCandidateElementView view,
            Map<String, ThreatCandidateElementView> byKey,
            List<ThreatCandidate> candidates,
            List<ThreatEnumerationLimitation> limitations) {
        for (var rule : definition.rules()) {
            if (!rule.targetElementKinds().contains(view.elementKind())) {
                continue;
            }
            evaluatePredicate(rule, view, byKey, limitations)
                    .ifPresent(facts -> candidates.add(new ThreatCandidate(
                            rule.ruleId(),
                            rule.category(),
                            rule.strideCategory(),
                            view.stableKey(),
                            view.elementKind(),
                            facts,
                            buildNarrative(rule, view))));
        }
    }

    /**
     * Evaluate the rule's predicate against the view. Returns the bounded matchedFacts map when the
     * predicate matches, or empty when it does not. DATA_FLOW predicates record a
     * DANGLING_FLOW_ENDPOINT limitation when a referenced endpoint is absent from the (valid-key) index.
     */
    private static Optional<Map<String, String>> evaluatePredicate(
            ThreatRule rule,
            ThreatCandidateElementView view,
            Map<String, ThreatCandidateElementView> byKey,
            List<ThreatEnumerationLimitation> limitations) {
        return switch (rule.predicate()) {
            case ALWAYS -> Optional.of(baseFacts(view, ThreatRuleMatchPredicate.ALWAYS));
            case CROSSES_TRUST_BOUNDARY -> matchCrossesTrustBoundary(view, byKey, limitations);
            case SOURCE_IS_EXTERNAL -> matchFlowEndpointIsExternal(view, byKey, limitations, true);
            case TARGET_IS_EXTERNAL -> matchFlowEndpointIsExternal(view, byKey, limitations, false);
            case HAS_DATA_CLASSIFICATION -> matchHasDataClassification(view);
            case HAS_TRUST_BOUNDARY -> matchHasTrustBoundary(view);
            case HAS_METADATA_TAG -> matchHasMetadataTag(rule, view);
        };
    }

    /** A facts map seeded with the always-present elementKind + predicate provenance entries. */
    private static LinkedHashMap<String, String> baseFacts(
            ThreatCandidateElementView view, ThreatRuleMatchPredicate predicate) {
        var facts = new LinkedHashMap<String, String>();
        facts.put(FACT_ELEMENT_KIND, view.elementKind().name());
        facts.put(FACT_PREDICATE, predicate.name());
        return facts;
    }

    private static ThreatEnumerationLimitation danglingEndpoint(
            ThreatCandidateElementView view, ThreatRuleMatchPredicate predicate) {
        return new ThreatEnumerationLimitation(
                ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT,
                "Flow endpoint not present in snapshot for " + predicate.name() + " predicate",
                view.stableKey());
    }

    private static Optional<Map<String, String>> matchCrossesTrustBoundary(
            ThreatCandidateElementView view,
            Map<String, ThreatCandidateElementView> byKey,
            List<ThreatEnumerationLimitation> limitations) {
        if (view.elementKind() != ArchitectureModelElementKind.DATA_FLOW) {
            return Optional.empty();
        }
        var source = byKey.get(view.flowSourceStableKey());
        var target = byKey.get(view.flowTargetStableKey());
        if (source == null || target == null) {
            limitations.add(danglingEndpoint(view, ThreatRuleMatchPredicate.CROSSES_TRUST_BOUNDARY));
            return Optional.empty();
        }
        var sourceTb = trimToNull(source.trustBoundaryKey());
        var targetTb = trimToNull(target.trustBoundaryKey());
        var flowTb = trimToNull(view.trustBoundaryKey());
        boolean crossesBoundary =
                (sourceTb != null && targetTb != null && !sourceTb.equals(targetTb)) || flowTb != null;
        if (!crossesBoundary) {
            return Optional.empty();
        }
        var facts = baseFacts(view, ThreatRuleMatchPredicate.CROSSES_TRUST_BOUNDARY);
        if (sourceTb != null) {
            facts.put("sourceTrustBoundaryKey", sourceTb);
        }
        if (targetTb != null) {
            facts.put("targetTrustBoundaryKey", targetTb);
        }
        if (flowTb != null) {
            facts.put("trustBoundaryKey", flowTb);
        }
        return Optional.of(facts);
    }

    private static Optional<Map<String, String>> matchFlowEndpointIsExternal(
            ThreatCandidateElementView view,
            Map<String, ThreatCandidateElementView> byKey,
            List<ThreatEnumerationLimitation> limitations,
            boolean source) {
        if (view.elementKind() != ArchitectureModelElementKind.DATA_FLOW) {
            return Optional.empty();
        }
        var predicate =
                source ? ThreatRuleMatchPredicate.SOURCE_IS_EXTERNAL : ThreatRuleMatchPredicate.TARGET_IS_EXTERNAL;
        var endpointKey = source ? view.flowSourceStableKey() : view.flowTargetStableKey();
        var endpoint = byKey.get(endpointKey);
        if (endpoint == null) {
            limitations.add(danglingEndpoint(view, predicate));
            return Optional.empty();
        }
        if (endpoint.elementKind() != ArchitectureModelElementKind.EXTERNAL_ENTITY) {
            return Optional.empty();
        }
        var facts = baseFacts(view, predicate);
        facts.put(source ? "sourceStableKey" : "targetStableKey", endpoint.stableKey());
        facts.put(
                source ? "sourceElementKind" : "targetElementKind",
                endpoint.elementKind().name());
        return Optional.of(facts);
    }

    private static Optional<Map<String, String>> matchHasDataClassification(ThreatCandidateElementView view) {
        var dcKey = trimToNull(view.dataClassificationKey());
        if (dcKey == null) {
            return Optional.empty();
        }
        var facts = baseFacts(view, ThreatRuleMatchPredicate.HAS_DATA_CLASSIFICATION);
        facts.put("dataClassificationKey", dcKey);
        return Optional.of(facts);
    }

    private static Optional<Map<String, String>> matchHasTrustBoundary(ThreatCandidateElementView view) {
        var tbKey = trimToNull(view.trustBoundaryKey());
        if (tbKey == null) {
            return Optional.empty();
        }
        var facts = baseFacts(view, ThreatRuleMatchPredicate.HAS_TRUST_BOUNDARY);
        facts.put("trustBoundaryKey", tbKey);
        return Optional.of(facts);
    }

    private static Optional<Map<String, String>> matchHasMetadataTag(ThreatRule rule, ThreatCandidateElementView view) {
        var tagKey = rule.metadataTagKey();
        if (!view.metadata().containsKey(tagKey)) {
            return Optional.empty();
        }
        var facts = baseFacts(view, ThreatRuleMatchPredicate.HAS_METADATA_TAG);
        facts.put("metadataTagKey", tagKey);
        return Optional.of(facts);
    }

    private static String buildNarrative(ThreatRule rule, ThreatCandidateElementView view) {
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
