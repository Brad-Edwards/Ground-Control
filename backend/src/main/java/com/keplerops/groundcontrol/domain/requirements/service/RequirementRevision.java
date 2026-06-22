package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import java.time.Instant;
import java.util.Map;

public record RequirementRevision(
        int revisionNumber,
        Instant timestamp,
        String revisionType,
        String actor,
        String reason,
        Requirement entity,
        Map<String, FieldChange> changes) {}
