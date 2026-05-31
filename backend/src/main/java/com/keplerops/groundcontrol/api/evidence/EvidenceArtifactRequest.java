package com.keplerops.groundcontrol.api.evidence;

import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.verification.state.AssuranceLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Wire-shape for evidence-artifact create / supersede (GC-M016 / ADR-045).
 *
 * <p>{@code expiresAt} and {@code validityWindowDays} are optional. When
 * {@code expiresAt} is set, the GC-I004 sweep job will publish an expiry
 * event the first time it runs after that instant; the artifact row itself
 * is never mutated by expiry (append-only is preserved).
 */
public record EvidenceArtifactRequest(
        @NotBlank @Size(max = 50) String uid,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 8000) String summary,
        @NotNull EvidenceType evidenceType,
        @NotBlank @Size(max = 200) String derivationMethod,
        @NotNull Instant derivedAt,
        AssuranceLevel assuranceLevel,
        @Size(max = 50) String confidence,
        @Size(max = 4000) String notes,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid EvidenceSourceRefDto> sources,
        Instant expiresAt,
        @Min(1) Integer validityWindowDays) {}
