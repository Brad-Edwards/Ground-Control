package com.keplerops.groundcontrol.domain.audit.service;

import com.keplerops.groundcontrol.domain.audit.repository.RevisionRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The one as-of resolution rule for Ground Control (ADR-084 §5): the canonical as-of coordinate
 * is the Envers revision number, resolved as the greatest revision whose timestamp is at or
 * before the requested instant (inclusive boundary).
 *
 * <p>This resolver is global, not project-scoped — a revision is a coordinate, not an
 * authorization. Project-scoped filtering remains the responsibility of each consumer (e.g.
 * {@code BaselineService} already filters requirements by project after resolving a revision).
 *
 * <p>{@link Optional#empty()} means "no revision" — including when the requested instant
 * precedes all recorded history. It is never coerced to a sentinel like {@code 0} here; any
 * aggregate that needs a local origin sentinel (e.g. {@code Baseline.revisionNumber}) maps
 * {@code Optional.empty()} to that sentinel itself, at its own boundary.
 */
@Service
public class AsOfRevisionResolver {

    private final RevisionRepository revisionRepository;

    public AsOfRevisionResolver(RevisionRepository revisionRepository) {
        this.revisionRepository = revisionRepository;
    }

    /** Greatest revision whose timestamp is at or before {@code asOf} (inclusive). */
    public Optional<Integer> resolveAsOf(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return revisionRepository.findGreatestRevisionAtOrBefore(asOf.toEpochMilli());
    }

    /** Greatest revision recorded, or empty when no revision has ever been created. */
    public Optional<Integer> currentRevision() {
        return revisionRepository.findGreatestRevision();
    }
}
