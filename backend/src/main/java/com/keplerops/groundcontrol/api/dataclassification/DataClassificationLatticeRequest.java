package com.keplerops.groundcontrol.api.dataclassification;

import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.FlowInput;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeCommand.LabelInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body to replace a project's data classification lattice (GC-GRC-006). Bean Validation
 * bounds the taxonomy and relation; the service performs the semantic lattice-soundness checks.
 */
public record DataClassificationLatticeRequest(
        @NotEmpty @Size(max = 50) List<@Valid LabelRequest> labels,
        @Size(max = 2000) List<@Valid FlowRequest> permittedFlows) {

    public DataClassificationLatticeCommand toCommand() {
        var labelInputs = labels.stream()
                .map(label -> new LabelInput(label.key(), label.displayName(), label.description(), label.rank()))
                .toList();
        var flowInputs = permittedFlows == null
                ? List.<FlowInput>of()
                : permittedFlows.stream()
                        .map(flow -> new FlowInput(flow.from(), flow.to()))
                        .toList();
        return new DataClassificationLatticeCommand(labelInputs, flowInputs);
    }

    public record LabelRequest(
            @NotBlank @Size(max = 120) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$") String key,
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 2000) String description,
            Integer rank) {}

    public record FlowRequest(@NotBlank @Size(max = 120) String from, @NotBlank @Size(max = 120) String to) {}
}
