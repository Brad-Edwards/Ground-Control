package com.keplerops.groundcontrol.api.requirements;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityMatrixService;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Traceability Matrix view per GC-Q003.
 *
 * <p>The literal {@code traceability/matrix} path is unambiguous under Spring's {@code PathPatternParser}
 * — it is not captured by the {@code \{id\}} pattern on {@link RequirementController}, exactly as the
 * sibling {@code /requirements/traceability/by-artifact} endpoint already relies on.
 */
@RestController
@RequestMapping("/api/v1/requirements")
@Validated
public class TraceabilityMatrixController {

    private final TraceabilityMatrixService matrixService;
    private final ProjectService projectService;

    public TraceabilityMatrixController(TraceabilityMatrixService matrixService, ProjectService projectService) {
        this.matrixService = matrixService;
        this.projectService = projectService;
    }

    @GetMapping("/traceability/matrix")
    public TraceabilityMatrixResponse matrix(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) Integer wave,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) LinkType linkType) {
        UUID projectId = projectService.resolveProjectId(project);
        return TraceabilityMatrixResponse.from(matrixService.matrix(projectId, wave, status, linkType));
    }
}
