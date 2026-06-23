package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import java.util.UUID;

public record CreateRequirementCommand(
        UUID projectId,
        String uid,
        String uidPrefix,
        String title,
        String statement,
        String rationale,
        RequirementType requirementType,
        Priority priority,
        Integer wave) {}
