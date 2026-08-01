package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Stateless helpers split out of {@link ResearchProvenanceService} under issue #1467
 * for the 500-LOC limit (docs/CODING_STANDARDS.md).
 *
 * Every method here touches no instance state, so it is static and the
 * original keeps a static import for each -- call sites are unchanged.
 */
final class ResearchProvenanceServiceSupport {

    private ResearchProvenanceServiceSupport() {}

    static final int SUBJECT_KEY_MAX = 200;
    static final int LOCATOR_MAX = 500;
    static final int HASH_MAX = 128;
    static final int EXTERNAL_ID_MAX = 200;
    static final int SUMMARY_MAX = 2000;
    static final int TOOL_NAME_MAX = 200;
    static final int TOOL_VERSION_MAX = 100;
    static final int ACTION_ID_MAX = 200;
    static final int IDEMPOTENCY_KEY_MAX = 200;

    static final String INVALID_NODE = "invalid_provenance_node";
    static final String FIELD = "field";
    static final String IDEMPOTENCY_FIELD = "idempotencyKey";
    static final String IDEMPOTENCY_CONFLICT = "provenance_idempotency_conflict";

    /**
     * Shared idempotent-replay gate. Returns the existing record when the key
     * matches a compatible payload; throws {@link ConflictException} when the key
     * was reused with a different payload (never a silent no-op that could
     * suppress or poison the durable provenance chain); returns empty when no key
     * or no existing record (the caller then inserts a new record).
     */
    static <T> Optional<T> replayIfPresent(String key, Function<String, Optional<T>> lookup, Predicate<T> compatible) {
        if (key == null) {
            return Optional.empty();
        }
        var existing = lookup.apply(key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!compatible.test(existing.get())) {
            throw new ConflictException(
                    "Idempotency key reused with a different payload",
                    IDEMPOTENCY_CONFLICT,
                    Map.of(FIELD, IDEMPOTENCY_FIELD));
        }
        return existing;
    }

    /** True when two nodes carry the same provenance payload (excludes id, status, audit, actor). */
    static boolean nodesEquivalent(ResearchProvenanceNode a, ResearchProvenanceNode b) {
        return a.getKind() == b.getKind()
                && Objects.equals(a.getSubjectKey(), b.getSubjectKey())
                && a.getStage() == b.getStage()
                && a.getArtifactType() == b.getArtifactType()
                && Objects.equals(a.getArtifactId(), b.getArtifactId())
                && Objects.equals(a.getAttemptNo(), b.getAttemptNo())
                && Objects.equals(a.getLocator(), b.getLocator())
                && Objects.equals(a.getContentHash(), b.getContentHash())
                && Objects.equals(a.getExternalIdentifier(), b.getExternalIdentifier())
                && Objects.equals(a.getSummary(), b.getSummary())
                && Objects.equals(a.getToolName(), b.getToolName())
                && Objects.equals(a.getToolVersion(), b.getToolVersion())
                && Objects.equals(a.getSourceActionId(), b.getSourceActionId());
    }

    static void validateNodeLengths(RecordProvenanceNodeCommand command, String subjectKey) {
        requireUnder(subjectKey, SUBJECT_KEY_MAX, "subjectKey");
        requireUnder(command.locator(), LOCATOR_MAX, "locator");
        requireUnder(command.contentHash(), HASH_MAX, "contentHash");
        requireUnder(command.externalIdentifier(), EXTERNAL_ID_MAX, "externalIdentifier");
        requireUnder(command.summary(), SUMMARY_MAX, "summary");
        requireUnder(command.toolName(), TOOL_NAME_MAX, "toolName");
        requireUnder(command.toolVersion(), TOOL_VERSION_MAX, "toolVersion");
        requireUnder(command.sourceActionId(), ACTION_ID_MAX, "sourceActionId");
        requireUnder(command.idempotencyKey(), IDEMPOTENCY_KEY_MAX, IDEMPOTENCY_FIELD);
    }

    static void requireUnder(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "Field " + field + " exceeds max length", INVALID_NODE, Map.of(FIELD, field, "max", max));
        }
    }
}
