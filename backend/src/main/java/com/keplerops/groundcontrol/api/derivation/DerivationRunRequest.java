package com.keplerops.groundcontrol.api.derivation;

import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DerivationRunRequest(
        @NotNull DerivationScopeMode scopeMode,
        @NotBlank @Size(min = 7, max = 64) @Pattern(regexp = "^[0-9a-fA-F]{7,64}$") String commitSha,
        @Size(min = 7, max = 64) @Pattern(regexp = "^[0-9a-fA-F]{7,64}$") String baseCommitSha,
        @Size(max = 200) List<@NotBlank @Size(max = 500) String> paths,
        @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 80) String> languages,
        @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 80) String> surfaces) {}
