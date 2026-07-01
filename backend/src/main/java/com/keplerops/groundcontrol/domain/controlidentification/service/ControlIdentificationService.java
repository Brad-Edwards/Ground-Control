package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.controlidentification.state.ControlCandidateSource;
import com.keplerops.groundcontrol.domain.controlidentification.state.ControlIdentificationGapReason;
import com.keplerops.groundcontrol.domain.controlpacks.model.ControlPackEntry;
import com.keplerops.groundcontrol.domain.controlpacks.repository.ControlPackEntryRepository;
import com.keplerops.groundcontrol.domain.controlpacks.state.ControlPackEntryStatus;
import com.keplerops.groundcontrol.domain.controlpacks.state.ControlPackLifecycleState;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationResult;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic control-identification engine (GC-GRC-008): maps enumerated threats to candidate
 * controls via a {@link ControlMappingRuleSet} (threat category → control objective → candidate
 * controls). No LLM involvement — given the same rule set, threats, and available controls the result
 * is byte-stable. Mirrors the {@link ThreatEnumerationService} sibling pattern (GC-GRC-007, ADR-058
 * §3): a pure static {@link #identify} method exercised by unit tests, and read-only service wrappers
 * that resolve enumerated threats and available controls before delegating.
 *
 * <p>Candidate controls are drawn from installed control packs and the project's existing controls;
 * each candidate carries implementation guidance and rule provenance. Where a rule fires but no control
 * matches, an explicit {@link ControlIdentificationGap} is emitted rather than dropping the candidate.
 */
@Service
public class ControlIdentificationService {

    public static final String SCHEMA_VERSION = "control-identification/v1";

    private static final Logger log = LoggerFactory.getLogger(ControlIdentificationService.class);

    private final ThreatEnumerationService threatEnumerationService;
    private final ControlRepository controlRepository;
    private final ControlPackEntryRepository controlPackEntryRepository;

    public ControlIdentificationService(
            ThreatEnumerationService threatEnumerationService,
            ControlRepository controlRepository,
            ControlPackEntryRepository controlPackEntryRepository) {
        this.threatEnumerationService = threatEnumerationService;
        this.controlRepository = controlRepository;
        this.controlPackEntryRepository = controlPackEntryRepository;
    }

    /**
     * Identify candidate controls for the threats enumerated against the project's latest
     * architecture-model snapshot, using the built-in default rule set.
     */
    @Transactional(readOnly = true)
    public ControlIdentificationResult identifyForLatestSnapshot(
            UUID projectId, String threatPackId, String versionConstraint) {
        var enumeration = threatEnumerationService.enumerateLatest(projectId, threatPackId, versionConstraint);
        return identifyForEnumeration(projectId, enumeration);
    }

    /**
     * Identify candidate controls for the threats enumerated against a specific architecture-model
     * snapshot, using the built-in default rule set.
     */
    @Transactional(readOnly = true)
    public ControlIdentificationResult identifyForSnapshot(
            UUID projectId, UUID snapshotId, String threatPackId, String versionConstraint) {
        var enumeration =
                threatEnumerationService.enumerateSnapshot(projectId, snapshotId, threatPackId, versionConstraint);
        return identifyForEnumeration(projectId, enumeration);
    }

    private ControlIdentificationResult identifyForEnumeration(UUID projectId, ThreatEnumerationResult enumeration) {
        var ruleSet = DefaultControlMappingRuleSet.standard();
        var threats = enumeration.candidates().stream()
                .map(MappableThreat::fromCandidate)
                .toList();
        var controls = loadAvailableControls(projectId);
        var result = identify(ruleSet, threats, controls);
        log.info(
                "control_identification: project={} threats={} controls={} candidates={} gaps={}",
                projectId,
                threats.size(),
                controls.size(),
                result.candidates().size(),
                result.gaps().size());
        return result;
    }

    /** Assemble the project's candidate-eligible controls with pack provenance and framework identifiers. */
    @Transactional(readOnly = true)
    public List<AvailableControl> loadAvailableControls(UUID projectId) {
        List<AvailableControl> available = new ArrayList<>();
        for (var control : controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            var entries = controlPackEntryRepository.findByControlId(control.getId());
            available.add(entries.isEmpty() ? projectControl(control) : packControl(control, entries));
        }
        return available;
    }

    private static AvailableControl projectControl(Control control) {
        Set<String> identifiers = new LinkedHashSet<>();
        addToken(identifiers, control.getCategory());
        return new AvailableControl(
                control.getId(),
                control.getUid(),
                control.getTitle(),
                control.getObjective(),
                control.getCategory(),
                control.getSource(),
                ControlCandidateSource.PROJECT_CONTROL,
                null,
                null,
                null,
                null,
                identifiers,
                true);
    }

    private static AvailableControl packControl(Control control, List<ControlPackEntry> entries) {
        // Only ACTIVE entries from an INSTALLED/UPGRADED pack are eligible. Eligibility, matching
        // identifiers, guidance, pack provenance, and the deterministic primary entry are ALL derived
        // from this same filtered set — so a superseded, removed, or non-installed pack entry can
        // never contribute a false candidate, nor can it mask a valid active entry by sorting first.
        var eligible = entries.stream()
                .filter(ControlIdentificationService::isEligibleEntry)
                .sorted(Comparator.comparing(ControlPackEntry::getEntryUid))
                .toList();
        boolean active = !eligible.isEmpty();
        // When no entry is eligible the control is inactive (the engine never matches it); its
        // provenance is still surfaced for display from the full, deterministically-ordered set.
        var source = active
                ? eligible
                : entries.stream()
                        .sorted(Comparator.comparing(ControlPackEntry::getEntryUid))
                        .toList();
        var primary = source.get(0);
        var pack = primary.getControlPack();
        Set<String> identifiers = new LinkedHashSet<>();
        for (var entry : source) {
            if (entry.getFrameworkMappings() != null) {
                for (var mapping : entry.getFrameworkMappings()) {
                    addFrameworkTokens(identifiers, mapping);
                }
            }
        }
        addToken(identifiers, control.getCategory());
        return new AvailableControl(
                control.getId(),
                control.getUid(),
                control.getTitle(),
                control.getObjective(),
                control.getCategory(),
                control.getSource(),
                ControlCandidateSource.CONTROL_PACK,
                pack.getPackId(),
                pack.getVersion(),
                pack.getChecksum(),
                primary.getImplementationGuidance(),
                identifiers,
                active);
    }

    private static boolean isEligibleEntry(ControlPackEntry entry) {
        if (entry.getEntryStatus() != ControlPackEntryStatus.ACTIVE) {
            return false;
        }
        var lifecycle = entry.getControlPack().getLifecycleState();
        return lifecycle == ControlPackLifecycleState.INSTALLED || lifecycle == ControlPackLifecycleState.UPGRADED;
    }

    private static void addFrameworkTokens(Set<String> identifiers, Map<String, Object> mapping) {
        var identifier = mapping.get("identifier");
        if (identifier == null || identifier.toString().isBlank()) {
            return;
        }
        var idStr = identifier.toString().trim();
        identifiers.add(idStr);
        var framework = mapping.get("framework");
        if (framework != null && !framework.toString().isBlank()) {
            identifiers.add(framework.toString().trim() + ":" + idStr);
        }
    }

    private static void addToken(Set<String> identifiers, String token) {
        if (token != null && !token.isBlank()) {
            identifiers.add(token.trim());
        }
    }

    /**
     * Pure control identification over a set of enumerated threats against a rule set and the project's
     * available controls. Deterministic and side-effect free: the same inputs always produce the same
     * ordered candidate and gap lists (candidates by {@code (threatRef, producingRuleId, controlUid)};
     * gaps by {@code (threatRef, producingRuleId, objectiveKey)}).
     */
    public static ControlIdentificationResult identify(
            ControlMappingRuleSet ruleSet, List<MappableThreat> threats, List<AvailableControl> controls) {

        List<ControlCandidate> candidates = new ArrayList<>();
        List<ControlIdentificationGap> gaps = new ArrayList<>();
        Set<String> seenCandidate = new LinkedHashSet<>();

        for (var threat : threats) {
            for (var rule : ruleSet.rules()) {
                if (!ruleApplies(rule, threat)) {
                    continue;
                }
                collectForThreatRule(ruleSet, rule, threat, controls, candidates, gaps, seenCandidate);
            }
        }

        candidates.sort(Comparator.comparing(ControlCandidate::threatRef)
                .thenComparing(ControlCandidate::producingRuleId)
                .thenComparing(ControlCandidate::controlUid));
        gaps.sort(Comparator.comparing(ControlIdentificationGap::threatRef)
                .thenComparing(ControlIdentificationGap::producingRuleId)
                .thenComparing(ControlIdentificationGap::objectiveKey));

        return new ControlIdentificationResult(
                SCHEMA_VERSION, ruleSet.ruleSetId(), ruleSet.version(), candidates, gaps);
    }

    private static boolean ruleApplies(ControlMappingRule rule, MappableThreat threat) {
        if (rule.category() != threat.category()) {
            return false;
        }
        return rule.strideCategory() == null || rule.strideCategory() == threat.strideCategory();
    }

    private static void collectForThreatRule(
            ControlMappingRuleSet ruleSet,
            ControlMappingRule rule,
            MappableThreat threat,
            List<AvailableControl> controls,
            List<ControlCandidate> candidates,
            List<ControlIdentificationGap> gaps,
            Set<String> seenCandidate) {
        boolean matchedAny = false;
        for (var control : controls) {
            if (!control.active()) {
                continue;
            }
            var matchedSelectors = matchedSelectors(rule, control);
            if (matchedSelectors.isEmpty()) {
                continue;
            }
            matchedAny = true;
            var key = threat.threatRef() + "|" + rule.ruleId() + "|" + control.controlId();
            if (seenCandidate.add(key)) {
                candidates.add(buildCandidate(ruleSet, rule, threat, control, matchedSelectors));
            }
        }
        if (!matchedAny) {
            var reason = controls.isEmpty()
                    ? ControlIdentificationGapReason.NO_CONTROLS_AVAILABLE
                    : ControlIdentificationGapReason.NO_MATCHING_CONTROL;
            gaps.add(new ControlIdentificationGap(
                    threat.category(),
                    threat.strideCategory(),
                    rule.objectiveKey(),
                    rule.ruleId(),
                    threat.threatRef(),
                    reason,
                    "No available control satisfies objective '" + rule.objectiveKey() + "' (selectors "
                            + new TreeSet<>(rule.frameworkSelectors()) + ") for this threat"));
        }
    }

    private static ControlCandidate buildCandidate(
            ControlMappingRuleSet ruleSet,
            ControlMappingRule rule,
            MappableThreat threat,
            AvailableControl control,
            Set<String> matchedSelectors) {
        var guidance = control.implementationGuidance() != null
                        && !control.implementationGuidance().isBlank()
                ? control.implementationGuidance()
                : rule.defaultGuidance();
        Map<String, String> matchedFacts = Map.of(
                "objective", rule.objectiveKey(),
                "matchedSelectors", String.join(",", matchedSelectors),
                "matchedFrameworkIds", String.join(",", matchedIdentifiers(rule, control)),
                "candidateSource", control.sourceKind().name());
        return new ControlCandidate(
                rule.ruleId(),
                ruleSet.ruleSetId(),
                ruleSet.version(),
                threat.category(),
                threat.strideCategory(),
                rule.objectiveKey(),
                threat.threatRef(),
                control.controlId(),
                control.controlUid(),
                control.sourceKind(),
                control.packId(),
                control.packVersion(),
                control.packChecksum(),
                guidance,
                matchedFacts,
                rule.rationale());
    }

    /** The rule selectors (sorted) that match at least one of the control's framework identifiers. */
    private static Set<String> matchedSelectors(ControlMappingRule rule, AvailableControl control) {
        var controlTokens = expandTokens(control.frameworkIdentifiers());
        Set<String> matched = new TreeSet<>();
        for (var selector : rule.frameworkSelectors()) {
            if (controlTokens.contains(selector.toUpperCase(Locale.ROOT))) {
                matched.add(selector);
            }
        }
        return matched;
    }

    /** The control's raw framework identifiers (sorted) that match at least one rule selector. */
    private static Set<String> matchedIdentifiers(ControlMappingRule rule, AvailableControl control) {
        Set<String> selectorsUpper = new LinkedHashSet<>();
        for (var selector : rule.frameworkSelectors()) {
            selectorsUpper.add(selector.toUpperCase(Locale.ROOT));
        }
        Set<String> matched = new TreeSet<>();
        for (var identifier : control.frameworkIdentifiers()) {
            var tokens = expandTokens(Set.of(identifier));
            if (tokens.stream().anyMatch(selectorsUpper::contains)) {
                matched.add(identifier);
            }
        }
        return matched;
    }

    /**
     * Expand raw framework identifiers into an upper-cased match-token set: the identifier itself, the
     * portion after a {@code framework:} prefix, and the control-family prefix before the first dash.
     * This lets a rule selector match either a specific control id ({@code IA-2}) or a family
     * ({@code IA}) without unbounded narrative search.
     */
    private static Set<String> expandTokens(Set<String> rawIdentifiers) {
        Set<String> tokens = new LinkedHashSet<>();
        for (var raw : rawIdentifiers) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            var upper = raw.trim().toUpperCase(Locale.ROOT);
            tokens.add(upper);
            int colon = upper.indexOf(':');
            var core = colon >= 0 ? upper.substring(colon + 1) : upper;
            tokens.add(core);
            int dash = core.indexOf('-');
            if (dash > 0) {
                tokens.add(core.substring(0, dash));
            }
        }
        return tokens;
    }
}
