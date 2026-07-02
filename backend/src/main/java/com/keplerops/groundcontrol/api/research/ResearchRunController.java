package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * GC-RSCH-R001/R003/F003/F006/F034/F036/N007/N011/N012/N013 — REST surface for the
 * {@link com.keplerops.groundcontrol.domain.research.model.ResearchRun} aggregate
 * (ADR-064 / ADR-065 / ADR-066 / ADR-067 / ADR-068). Routes live under
 * {@code /api/v1/research-runs/**} so the shared auth + actor-filter chains apply
 * via the {@code /api/v1/**} {@code .authenticated()} rule in {@code ApiPathMatrix}.
 * The controller only resolves the project and forwards a request DTO's
 * {@code toCommand()} to the service; all lifecycle legality is owned by
 * {@link ResearchRunService}, and the DTOs (not the controller) name the domain
 * enums (ArchUnit boundary).
 */
@RestController
@RequestMapping("/api/v1/research-runs")
public class ResearchRunController {

    private final ResearchRunService researchRunService;
    private final ProjectService projectService;

    public ResearchRunController(ResearchRunService researchRunService, ProjectService projectService) {
        this.researchRunService = researchRunService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunResponse start(
            @Valid @RequestBody ResearchRunRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ResearchRunResponse.from(researchRunService.start(request.toCommand(projectId)));
    }

    @GetMapping
    public List<ResearchRunResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return researchRunService.listByProject(projectId).stream()
                .map(ResearchRunResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResearchRunResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(researchRunService.getById(projectId, id));
    }

    @GetMapping("/uid/{uid}")
    public ResearchRunResponse getByUid(@PathVariable String uid, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(researchRunService.getByUid(projectId, uid));
    }

    @GetMapping("/{id}/snapshot")
    public ResearchRunSnapshotResponse snapshot(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunSnapshotResponse.from(researchRunService.getSnapshot(projectId, id));
    }

    @GetMapping("/{id}/artifacts")
    public List<ResearchRunArtifactResponse> listArtifacts(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return researchRunService.listArtifacts(projectId, id).stream()
                .map(ResearchRunArtifactResponse::from)
                .toList();
    }

    @GetMapping("/{id}/gates")
    public List<ResearchRunGateResponse> listGates(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return researchRunService.listGates(projectId, id).stream()
                .map(ResearchRunGateResponse::from)
                .toList();
    }

    @PostMapping("/{id}/artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunArtifactResponse recordArtifact(
            @PathVariable UUID id,
            @Valid @RequestBody RecordArtifactRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunArtifactResponse.from(researchRunService.recordArtifact(projectId, id, request.toCommand()));
    }

    @PostMapping("/{id}/advance")
    public ResearchRunResponse advance(
            @PathVariable UUID id,
            @Valid @RequestBody AdvanceStageRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(researchRunService.advanceStage(projectId, id, request.toCommand()));
    }

    @PostMapping("/{id}/gates/decision")
    public ResearchRunGateResponse decideGate(
            @PathVariable UUID id,
            @Valid @RequestBody GateDecisionRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunGateResponse.from(researchRunService.resolveGate(projectId, id, request.toCommand()));
    }

    // GC-RSCH-F004 / ADR-066 — gate decision audit log
    @GetMapping("/{id}/gates/decision-log")
    public List<ResearchRunGateDecisionLogResponse> listGateDecisionLog(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return researchRunService.listGateDecisionLog(projectId, id).stream()
                .map(ResearchRunGateDecisionLogResponse::from)
                .toList();
    }

    // GC-RSCH-F034 / ADR-067 — run-scoped review comments
    @PostMapping("/{id}/review-comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunReviewCommentResponse addReviewComment(
            @PathVariable UUID id,
            @Valid @RequestBody AddReviewCommentRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunReviewCommentResponse.from(
                researchRunService.addReviewComment(projectId, id, request.toCommand()));
    }

    @GetMapping("/{id}/review-comments")
    public List<ResearchRunReviewCommentResponse> listReviewComments(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return researchRunService.listReviewComments(projectId, id).stream()
                .map(ResearchRunReviewCommentResponse::from)
                .toList();
    }

    @PostMapping("/{id}/review-comments/{commentId}/resolve")
    public ResearchRunReviewCommentResponse resolveReviewComment(
            @PathVariable UUID id,
            @PathVariable UUID commentId,
            @Valid @RequestBody ResolveReviewCommentRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunReviewCommentResponse.from(
                researchRunService.resolveReviewComment(projectId, id, commentId, request.toCommand()));
    }

    // GC-RSCH-N012 / ADR-068 — explainability / rationale ledger
    @PostMapping("/{id}/rationale")
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunRationaleEntryResponse addRationaleEntry(
            @PathVariable UUID id,
            @Valid @RequestBody AddRationaleEntryRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunRationaleEntryResponse.from(
                researchRunService.addRationaleEntry(projectId, id, request.toCommand()));
    }

    @GetMapping("/{id}/rationale")
    public List<ResearchRunRationaleEntryResponse> listRationale(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return researchRunService.listRationale(projectId, id).stream()
                .map(ResearchRunRationaleEntryResponse::from)
                .toList();
    }

    // GC-RSCH-N013 / ADR-068 §4 — accountability disclosure
    @PostMapping("/{id}/disclosure")
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunDisclosureResponse createDisclosure(
            @PathVariable UUID id,
            @Valid @RequestBody CreateDisclosureRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunDisclosureResponse.from(
                researchRunService.createDisclosure(projectId, id, request.toCommand()));
    }

    @GetMapping("/{id}/disclosure")
    public ResearchRunDisclosureResponse getDisclosure(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunDisclosureResponse.from(researchRunService.getDisclosure(projectId, id));
    }

    @PostMapping("/{id}/disclosure/{disclosureId}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunDisclosureEntryResponse addDisclosureEntry(
            @PathVariable UUID id,
            @PathVariable UUID disclosureId,
            @Valid @RequestBody AddDisclosureEntryRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunDisclosureEntryResponse.from(
                researchRunService.addDisclosureEntry(projectId, id, disclosureId, request.toCommand()));
    }

    // GC-RSCH-F006 / ADR-078 — backend-owned methodology catalog (global reference
    // data, no project/run scope). Declared before the {id}-scoped methodology
    // routes so the literal "methodology" path segment is not captured as a run id.
    @GetMapping("/methodology/catalog")
    public MethodologyCatalogResponse methodologyCatalog() {
        return MethodologyCatalogResponse.from(researchRunService.listMethodologyCatalog());
    }

    // GC-RSCH-F006 — methodology selection + source coverage gate
    @PostMapping("/{id}/methodology/selection")
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunMethodologySelectionResponse selectMethodology(
            @PathVariable UUID id,
            @Valid @RequestBody SelectMethodologyRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunMethodologySelectionResponse.from(
                researchRunService.selectMethodology(projectId, id, request.toCommand()));
    }

    @GetMapping("/{id}/methodology/selection")
    public ResearchRunMethodologySelectionResponse getMethodologySelection(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunMethodologySelectionResponse.from(researchRunService.getMethodologySelection(projectId, id));
    }

    @PostMapping("/{id}/methodology/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public ResearchRunMethodologySourceResponse recordMethodologySource(
            @PathVariable UUID id,
            @Valid @RequestBody RecordMethodologySourceRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunMethodologySourceResponse.from(
                researchRunService.recordMethodologySource(projectId, id, request.toCommand()));
    }

    @PatchMapping("/{id}/methodology/sources/{sourceId}")
    public ResearchRunMethodologySourceResponse updateMethodologySourceState(
            @PathVariable UUID id,
            @PathVariable UUID sourceId,
            @Valid @RequestBody UpdateMethodologySourceStateRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunMethodologySourceResponse.from(
                researchRunService.updateMethodologySourceState(projectId, id, sourceId, request.toCommand()));
    }

    @GetMapping("/{id}/methodology/sources")
    public List<ResearchRunMethodologySourceResponse> listMethodologySources(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return researchRunService.listMethodologySources(projectId, id).stream()
                .map(ResearchRunMethodologySourceResponse::from)
                .toList();
    }

    // GC-RSCH-F007 / GC-RSCH-F008 / ADR-080 — structured methodology requirements
    // contract behind the METHODOLOGY_REQUIREMENTS artifact.
    @PostMapping("/{id}/methodology/requirements-contract")
    @ResponseStatus(HttpStatus.CREATED)
    public MethodologyRequirementsContractResponse recordMethodologyRequirementsContract(
            @PathVariable UUID id,
            @Valid @RequestBody RecordMethodologyRequirementsContractRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return MethodologyRequirementsContractResponse.from(
                researchRunService.recordMethodologyRequirementsContract(projectId, id, request.toCommand()));
    }

    @GetMapping("/{id}/methodology/requirements-contract")
    public MethodologyRequirementsContractResponse getMethodologyRequirementsContract(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return MethodologyRequirementsContractResponse.from(
                researchRunService.getMethodologyRequirementsContract(projectId, id));
    }

    // GC-RSCH-F008 / GC-RSCH-F009 / ADR-081 — structured protocol plan behind
    // the PROTOCOL_PLAN artifact.
    @PostMapping("/{id}/protocol-plan")
    @ResponseStatus(HttpStatus.CREATED)
    public ProtocolPlanResponse recordProtocolPlan(
            @PathVariable UUID id,
            @Valid @RequestBody RecordProtocolPlanRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ProtocolPlanResponse.from(researchRunService.recordProtocolPlan(projectId, id, request.toCommand()));
    }

    @GetMapping("/{id}/protocol-plan")
    public ProtocolPlanResponse getProtocolPlan(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ProtocolPlanResponse.from(researchRunService.getProtocolPlan(projectId, id));
    }

    @PostMapping("/{id}/stop")
    public ResearchRunResponse stop(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(researchRunService.stop(projectId, id));
    }

    @PostMapping("/{id}/fail")
    public ResearchRunResponse fail(
            @PathVariable UUID id,
            @Valid @RequestBody FailRunRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(researchRunService.fail(projectId, id, request.toCommand()));
    }

    @PostMapping("/{id}/resume")
    public ResearchRunResponse resume(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(researchRunService.resume(projectId, id));
    }

    @PostMapping("/{id}/complete")
    public ResearchRunResponse complete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(researchRunService.complete(projectId, id));
    }

    @PostMapping("/{id}/usage")
    public ResearchRunResponse recordUsage(
            @PathVariable UUID id,
            @Valid @RequestBody RecordUsageRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ResearchRunResponse.from(
                researchRunService.recordUsage(projectId, id, request.tokens(), request.costUsdMicros()));
    }
}
