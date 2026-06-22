package com.keplerops.groundcontrol.api.riskappetite;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskappetite.service.CreateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskappetite.service.RiskAppetiteProfileService;
import com.keplerops.groundcontrol.domain.riskappetite.service.UpdateRiskAppetiteProfileCommand;
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

/**
 * CRUD surface for organizational risk appetite and tolerance profiles (GC-T005). Writes are
 * restricted to ROLE_ADMIN in {@code ApiPathMatrix} because appetite/tolerance governs org-wide
 * escalation policy; reads are available to any authenticated caller.
 */
@RestController
@RequestMapping("/api/v1/risk-appetite-profiles")
public class RiskAppetiteProfileController {

    private final RiskAppetiteProfileService riskAppetiteProfileService;
    private final ProjectService projectService;

    public RiskAppetiteProfileController(
            RiskAppetiteProfileService riskAppetiteProfileService, ProjectService projectService) {
        this.riskAppetiteProfileService = riskAppetiteProfileService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiskAppetiteProfileResponse create(
            @Valid @RequestBody RiskAppetiteProfileRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return RiskAppetiteProfileResponse.from(riskAppetiteProfileService.create(new CreateRiskAppetiteProfileCommand(
                projectId,
                request.appetiteKey(),
                request.name(),
                request.version(),
                request.methodologyFamily(),
                request.appetiteStatement(),
                request.toleranceThresholds(),
                request.status(),
                request.effectiveFrom(),
                request.effectiveTo())));
    }

    @GetMapping
    public List<RiskAppetiteProfileResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return riskAppetiteProfileService.listByProject(projectId).stream()
                .map(RiskAppetiteProfileResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public RiskAppetiteProfileResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskAppetiteProfileResponse.from(riskAppetiteProfileService.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public RiskAppetiteProfileResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRiskAppetiteProfileRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskAppetiteProfileResponse.from(riskAppetiteProfileService.update(
                projectId,
                id,
                new UpdateRiskAppetiteProfileCommand(
                        request.name(),
                        request.version(),
                        request.methodologyFamily(),
                        request.appetiteStatement(),
                        request.toleranceThresholds(),
                        request.status(),
                        request.effectiveFrom(),
                        request.effectiveTo())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        riskAppetiteProfileService.delete(projectId, id);
    }
}
