package com.keplerops.groundcontrol.api.packregistry;

import com.keplerops.groundcontrol.domain.packregistry.service.EmptyPackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportOptions;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportService;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryService;
import com.keplerops.groundcontrol.domain.packregistry.service.PackResolver;
import com.keplerops.groundcontrol.domain.packregistry.service.RegisterPackCommand;
import com.keplerops.groundcontrol.domain.packregistry.service.UpdatePackRegistryEntryCommand;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/pack-registry")
public class PackRegistryController {

    private final PackRegistryService registryService;
    private final PackRegistryImportService importService;
    private final PackResolver packResolver;
    private final ProjectService projectService;
    private final PackRegistryAccessGuard accessGuard;

    public PackRegistryController(
            PackRegistryService registryService,
            PackRegistryImportService importService,
            PackResolver packResolver,
            ProjectService projectService,
            PackRegistryAccessGuard accessGuard) {
        this.registryService = registryService;
        this.importService = importService;
        this.packResolver = packResolver;
        this.projectService = projectService;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PackRegistryEntryResponse register(
            @Valid @RequestBody RegisterPackRequest request, @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.resolveProjectId(project);
        var result = registryService.registerEntry(new RegisterPackCommand(
                projectId,
                request.packId(),
                request.packType(),
                request.version(),
                request.publisher(),
                request.description(),
                request.sourceUrl(),
                request.checksum(),
                request.signatureInfo(),
                request.compatibility(),
                PackDependencyRequest.toDomainList(request.dependencies()),
                EmptyPackRegistrationContent.INSTANCE,
                request.provenance(),
                request.registryMetadata()));
        return PackRegistryEntryResponse.from(result);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PackRegistryEntryResponse importEntry(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart(value = "options", required = false) PackRegistryImportRequest request,
            @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.resolveProjectId(project);
        var bytes = readFileBytes(file);
        var result = importService.importEntry(
                projectId,
                bytes,
                request != null
                        ? request.toOptions()
                        : new PackRegistryImportOptions(
                                null, null, null, null, null, null, null, null, null, null, null, null));
        return PackRegistryEntryResponse.from(result);
    }

    @GetMapping
    public List<PackRegistryEntryResponse> list(
            @RequestParam(required = false) String project, @RequestParam(required = false) PackType packType) {
        accessGuard.requireAdminActor();
        var projectId = projectService.resolveProjectId(project);
        var entries = packType != null
                ? registryService.listEntries(projectId, packType)
                : registryService.listEntries(projectId);
        return entries.stream().map(PackRegistryEntryResponse::from).toList();
    }

    @GetMapping("/{packId}")
    public List<PackRegistryEntryResponse> listVersions(
            @PathVariable String packId, @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.requireProjectId(project);
        return registryService.listVersions(projectId, packId).stream()
                .map(PackRegistryEntryResponse::from)
                .toList();
    }

    @GetMapping("/{packId}/{version}")
    public PackRegistryEntryResponse getEntry(
            @PathVariable String packId, @PathVariable String version, @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.requireProjectId(project);
        return PackRegistryEntryResponse.from(registryService.findEntry(projectId, packId, version));
    }

    @PutMapping("/{packId}/{version}")
    public PackRegistryEntryResponse updateEntry(
            @PathVariable String packId,
            @PathVariable String version,
            @Valid @RequestBody UpdatePackRegistryEntryRequest request,
            @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.requireProjectId(project);
        var result = registryService.updateEntry(
                projectId,
                packId,
                version,
                new UpdatePackRegistryEntryCommand(
                        request.publisher(),
                        request.description(),
                        request.sourceUrl(),
                        request.checksum(),
                        request.signatureInfo(),
                        request.compatibility(),
                        PackDependencyRequest.toDomainList(request.dependencies()),
                        null,
                        request.provenance(),
                        request.registryMetadata()));
        return PackRegistryEntryResponse.from(result);
    }

    @PutMapping("/{packId}/{version}/withdraw")
    public PackRegistryEntryResponse withdraw(
            @PathVariable String packId, @PathVariable String version, @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.requireProjectId(project);
        return PackRegistryEntryResponse.from(registryService.withdrawEntry(projectId, packId, version));
    }

    @DeleteMapping("/{packId}/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(
            @PathVariable String packId, @PathVariable String version, @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.requireProjectId(project);
        registryService.deleteEntry(projectId, packId, version);
    }

    @PostMapping("/resolve")
    public ResolvedPackResponse resolve(
            @Valid @RequestBody ResolvePackRequest request, @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.requireProjectId(project);
        var resolved = packResolver.resolve(projectId, request.packId(), request.versionConstraint());
        var compatible = packResolver.checkCompatibility(resolved);
        return ResolvedPackResponse.from(resolved, compatible, packResolver);
    }

    @PostMapping("/check-compatibility")
    public CompatibilityCheckResponse checkCompatibility(
            @Valid @RequestBody ResolvePackRequest request, @RequestParam(required = false) String project) {
        accessGuard.requireAdminActor();
        var projectId = projectService.requireProjectId(project);
        var resolved = packResolver.resolve(projectId, request.packId(), request.versionConstraint());
        var compatible = packResolver.checkCompatibility(resolved);
        return new CompatibilityCheckResponse(request.packId(), resolved.resolvedVersion(), compatible);
    }

    private static byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new com.keplerops.groundcontrol.domain.exception.GroundControlException(
                    "Failed to read uploaded file: " + e.getMessage(), "file_read_error", e);
        }
    }
}
