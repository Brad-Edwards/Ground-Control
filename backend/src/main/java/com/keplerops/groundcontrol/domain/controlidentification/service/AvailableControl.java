package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.controlidentification.state.ControlCandidateSource;
import java.util.Set;
import java.util.UUID;

/**
 * JPA-decoupled projection of a candidate-eligible control (GC-GRC-008): a project's catalog
 * {@code Control} together with the provenance and framework identifiers needed to match and cite it.
 * Assembled by {@code ControlIdentificationService} from {@code Control} + its installed
 * {@code ControlPackEntry} (when pack-backed) so the pure mapping engine never touches JPA.
 *
 * <p>{@code frameworkIdentifiers} are the recognized-framework references the control satisfies
 * (e.g. {@code NIST_800_53:AC-3}, {@code AC-3}, or a project control's {@code category} tag). The
 * engine matches these against a rule's selectors and records the matches as candidate provenance.
 */
public record AvailableControl(
        UUID controlId,
        String controlUid,
        String title,
        String objective,
        String category,
        String source,
        ControlCandidateSource sourceKind,
        String packId,
        String packVersion,
        String packChecksum,
        String implementationGuidance,
        Set<String> frameworkIdentifiers,
        boolean active) {

    public AvailableControl {
        if (controlId == null) {
            throw new IllegalArgumentException("AvailableControl controlId must not be null");
        }
        if (controlUid == null || controlUid.isBlank()) {
            throw new IllegalArgumentException("AvailableControl controlUid must not be blank");
        }
        if (sourceKind == null) {
            throw new IllegalArgumentException("AvailableControl sourceKind must not be null");
        }
        frameworkIdentifiers = frameworkIdentifiers == null ? Set.of() : Set.copyOf(frameworkIdentifiers);
    }
}
