package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import java.time.Instant;

public record DerivationCaptureLimitDraft(
        String adapterId,
        CaptureLimitReason reason,
        String language,
        String surface,
        String detail,
        String commitSha,
        Instant capturedAt) {}
