package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAppetiteProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateRiskAppetiteProfileCommand;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk-appetite-profiles")
public class RiskAppetiteProfileController {

    private final RiskAppetiteProfileService service;
    private final ProjectService projectService;

    public RiskAppetiteProfileController(RiskAppetiteProfileService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiskAppetiteProfileResponse create(
            @Valid @RequestBody RiskAppetiteProfileRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return RiskAppetiteProfileResponse.from(service.create(new CreateRiskAppetiteProfileCommand(
                projectId,
                request.profileKey(),
                request.name(),
                request.version(),
                request.appetiteStatement(),
                request.owner(),
                request.active(),
                request.tolerances())));
    }

    @GetMapping
    public List<RiskAppetiteProfileResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return service.listByProject(projectId).stream()
                .map(RiskAppetiteProfileResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public RiskAppetiteProfileResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskAppetiteProfileResponse.from(service.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public RiskAppetiteProfileResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRiskAppetiteProfileRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskAppetiteProfileResponse.from(service.update(
                projectId,
                id,
                new UpdateRiskAppetiteProfileCommand(
                        request.name(),
                        request.version(),
                        request.appetiteStatement(),
                        request.owner(),
                        request.active(),
                        request.tolerances())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        service.delete(projectId, id);
    }
}
