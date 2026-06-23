package com.keplerops.groundcontrol.api.requirements;

import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequirementRequest(
        @Size(max = 50) String uid,
        @NotBlank @Size(max = 255) String title,
        @NotBlank String statement,
        String rationale,
        RequirementType requirementType,
        Priority priority,
        Integer wave,
        @Size(max = 40) String uidPrefix) {

    @AssertTrue(message = "Exactly one of uid or uidPrefix must be provided (non-blank)")
    public boolean isExactlyOneOfUidOrUidPrefixPresent() {
        boolean hasUid = uid != null && !uid.isBlank();
        boolean hasPrefix = uidPrefix != null && !uidPrefix.isBlank();
        return hasUid ^ hasPrefix;
    }
}
