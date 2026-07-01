package com.keplerops.groundcontrol.api.packregistry;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Admin registration input for a single THREAT_RULE_PACK rule (GC-GRC-007). Shape-level
 * constraints are enforced here by Bean Validation; the deeper rule invariants (predicate /
 * metadataTagKey coupling) are enforced at the domain write boundary in
 * {@link com.keplerops.groundcontrol.domain.packregistry.service.ThreatRulePackTypeHandler}.
 */
public record ThreatRuleEntryDefinitionRequest(
        @NotEmpty @Size(max = 200) String ruleId,
        @Size(max = 300) String title,
        @NotNull ThreatRuleCategory category,
        @NotNull StrideCategory strideCategory,
        @NotEmpty Set<ArchitectureModelElementKind> targetElementKinds,
        @NotNull ThreatRuleMatchPredicate predicate,
        @Size(max = 200) String metadataTagKey,
        String narrativeSkeleton,
        String rationale) {}
