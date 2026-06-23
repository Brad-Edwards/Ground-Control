package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.documents.model.ContentType;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.documents.repository.SectionRepository;
import com.keplerops.groundcontrol.domain.documents.service.CreateDocumentCommand;
import com.keplerops.groundcontrol.domain.documents.service.CreateSectionCommand;
import com.keplerops.groundcontrol.domain.documents.service.CreateSectionContentCommand;
import com.keplerops.groundcontrol.domain.documents.service.DocumentService;
import com.keplerops.groundcontrol.domain.documents.service.SectionContentService;
import com.keplerops.groundcontrol.domain.documents.service.SectionService;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementImport;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementImportRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.ImportSourceType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final String LOG_RELATION_FAILED = "import_relation_failed: source={} target={} error={}";
    private static final String PHASE_RELATIONS = "relations";

    private final RequirementService requirementService;
    private final TraceabilityService traceabilityService;
    private final RequirementRepository requirementRepository;
    private final RequirementRelationRepository relationRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;
    private final RequirementImportRepository importRepository;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final SectionService sectionService;
    private final SectionRepository sectionRepository;
    private final SectionContentService sectionContentService;

    public ImportService(
            RequirementService requirementService,
            TraceabilityService traceabilityService,
            RequirementRepository requirementRepository,
            RequirementRelationRepository relationRepository,
            TraceabilityLinkRepository traceabilityLinkRepository,
            RequirementImportRepository importRepository,
            DocumentService documentService,
            DocumentRepository documentRepository,
            SectionService sectionService,
            SectionRepository sectionRepository,
            SectionContentService sectionContentService) {
        this.requirementService = requirementService;
        this.traceabilityService = traceabilityService;
        this.requirementRepository = requirementRepository;
        this.relationRepository = relationRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
        this.importRepository = importRepository;
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.sectionService = sectionService;
        this.sectionRepository = sectionRepository;
        this.sectionContentService = sectionContentService;
    }

    public ImportResult importStrictdoc(UUID projectId, String filename, String content) {
        SdocDocument doc = SdocParser.parse(content);
        var reqs = doc.requirements().stream()
                .map(r -> new ParsedRequirement(r.uid(), r.title(), r.statement(), r.wave(), r.parentUids()))
                .toList();
        var counters = new ImportCounters();
        List<ImportError> errors = new ArrayList<>();

        var uidToId = upsertRequirements(projectId, reqs, counters, errors);
        createParentRelations(projectId, reqs, uidToId, counters, errors);
        createTraceabilityLinks(doc.requirements(), uidToId, counters, errors);
        createDocumentStructure(projectId, doc, filename, uidToId, counters, errors);

        return saveAuditAndBuildResult(ImportSourceType.STRICTDOC, filename, reqs.size(), counters, errors);
    }

    public ImportResult importReqif(UUID projectId, String filename, String content) {
        ReqifParseResult parsed = ReqifParser.parse(content);
        var reqs = parsed.requirements().stream()
                .map(r -> new ParsedRequirement(r.identifier(), r.title(), r.statement(), null, r.parentIdentifiers()))
                .toList();
        var counters = new ImportCounters();
        List<ImportError> errors = new ArrayList<>();

        var uidToId = upsertRequirements(projectId, reqs, counters, errors);
        createParentRelations(projectId, reqs, uidToId, counters, errors);
        createExplicitRelations(projectId, parsed.relations(), uidToId, counters, errors);

        return saveAuditAndBuildResult(ImportSourceType.REQIF, filename, reqs.size(), counters, errors);
    }

    // -----------------------------------------------------------------------
    // Requirement upsert
    // -----------------------------------------------------------------------

    private Map<String, UUID> upsertRequirements(
            UUID projectId, List<ParsedRequirement> requirements, ImportCounters counters, List<ImportError> errors) {
        Map<String, UUID> uidToId = new HashMap<>();
        for (ParsedRequirement req : requirements) {
            try {
                var existing = requirementRepository.findByProjectIdAndUidIgnoreCase(projectId, req.uid());
                UUID reqId;
                if (existing.isPresent()) {
                    var cmd = new UpdateRequirementCommand(
                            req.title(), req.statement(), null, RequirementType.FUNCTIONAL, Priority.MUST, req.wave());
                    var updated = requirementService.update(existing.get().getId(), cmd);
                    reqId = updated.getId();
                    counters.requirementsUpdated++;
                } else {
                    var cmd = new CreateRequirementCommand(
                            projectId,
                            req.uid(),
                            null,
                            req.title(),
                            req.statement(),
                            null,
                            RequirementType.FUNCTIONAL,
                            Priority.MUST,
                            req.wave());
                    var created = requirementService.create(cmd);
                    reqId = created.getId();
                    counters.requirementsCreated++;
                }
                uidToId.put(req.uid(), reqId);
            } catch (ConflictException | NotFoundException | DomainValidationException e) {
                log.warn("import_requirement_failed: uid={} error={}", req.uid(), e.getMessage());
                errors.add(new ImportError("requirements", req.uid(), e.getMessage(), null, null, null));
            }
        }
        return uidToId;
    }

    // -----------------------------------------------------------------------
    // Relations
    // -----------------------------------------------------------------------

    private void createParentRelations(
            UUID projectId,
            List<ParsedRequirement> requirements,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        for (ParsedRequirement req : requirements) {
            UUID childId = uidToId.get(req.uid());
            if (childId == null) {
                continue;
            }
            for (String parentUid : req.parentUids()) {
                processParentRelation(projectId, req, childId, parentUid, uidToId, counters, errors);
            }
        }
    }

    private void processParentRelation(
            UUID projectId,
            ParsedRequirement req,
            UUID childId,
            String parentUid,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        try {
            UUID parentId = resolveRequirementId(projectId, parentUid, uidToId);
            if (parentId == null) {
                errors.add(new ImportError(
                        PHASE_RELATIONS, req.uid(), "Parent not found: " + parentUid, null, null, null));
            } else if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                    childId, parentId, RelationType.PARENT)) {
                counters.relationsSkipped++;
            } else {
                requirementService.createRelation(childId, parentId, RelationType.PARENT);
                counters.relationsCreated++;
            }
        } catch (ConflictException | NotFoundException | DomainValidationException e) {
            log.warn(LOG_RELATION_FAILED, req.uid(), parentUid, e.getMessage());
            errors.add(new ImportError(PHASE_RELATIONS, req.uid(), e.getMessage(), parentUid, null, null));
        }
    }

    private UUID resolveRequirementId(UUID projectId, String uid, Map<String, UUID> uidToId) {
        UUID id = uidToId.get(uid);
        if (id != null) {
            return id;
        }
        var opt = requirementRepository.findByProjectIdAndUidIgnoreCase(projectId, uid);
        return opt.map(r -> r.getId()).orElse(null);
    }

    private void createExplicitRelations(
            UUID projectId,
            List<ReqifRelation> relations,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        for (ReqifRelation rel : relations) {
            processExplicitRelation(projectId, rel, uidToId, counters, errors);
        }
    }

    private void processExplicitRelation(
            UUID projectId,
            ReqifRelation rel,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        try {
            UUID sourceId = resolveRequirementId(projectId, rel.sourceIdentifier(), uidToId);
            if (sourceId == null) {
                errors.add(new ImportError(
                        PHASE_RELATIONS,
                        rel.sourceIdentifier(),
                        "Source not found: " + rel.sourceIdentifier(),
                        null,
                        null,
                        null));
                return;
            }
            UUID targetId = resolveRequirementId(projectId, rel.targetIdentifier(), uidToId);
            if (targetId == null) {
                errors.add(new ImportError(
                        PHASE_RELATIONS,
                        rel.targetIdentifier(),
                        "Target not found: " + rel.targetIdentifier(),
                        null,
                        null,
                        null));
                return;
            }
            if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(sourceId, targetId, rel.relationType())) {
                counters.relationsSkipped++;
            } else {
                requirementService.createRelation(sourceId, targetId, rel.relationType());
                counters.relationsCreated++;
            }
        } catch (ConflictException | NotFoundException | DomainValidationException e) {
            log.warn(LOG_RELATION_FAILED, rel.sourceIdentifier(), rel.targetIdentifier(), e.getMessage());
            errors.add(new ImportError(
                    PHASE_RELATIONS, rel.sourceIdentifier(), e.getMessage(), null, rel.targetIdentifier(), null));
        }
    }

    // -----------------------------------------------------------------------
    // Traceability links
    // -----------------------------------------------------------------------

    private void createTraceabilityLinks(
            List<SdocRequirement> parsed,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        for (SdocRequirement sdocReq : parsed) {
            UUID reqId = uidToId.get(sdocReq.uid());
            if (reqId == null) {
                continue;
            }
            for (Integer issueNum : sdocReq.issueRefs()) {
                try {
                    String artifactId = String.valueOf(issueNum);
                    if (traceabilityLinkRepository.existsByRequirementIdAndArtifactTypeAndArtifactIdentifierAndLinkType(
                            reqId, ArtifactType.GITHUB_ISSUE, artifactId, LinkType.IMPLEMENTS)) {
                        counters.traceabilityLinksSkipped++;
                        continue;
                    }
                    var cmd = new CreateTraceabilityLinkCommand(
                            ArtifactType.GITHUB_ISSUE, artifactId, null, null, LinkType.IMPLEMENTS);
                    traceabilityService.createLinkUnchecked(reqId, cmd);
                    counters.traceabilityLinksCreated++;
                } catch (ConflictException | NotFoundException | DomainValidationException e) {
                    log.warn(
                            "import_traceability_link_failed: uid={} issue={} error={}",
                            sdocReq.uid(),
                            issueNum,
                            e.getMessage());
                    errors.add(new ImportError(
                            "traceability", sdocReq.uid(), e.getMessage(), null, null, String.valueOf(issueNum)));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Document structure creation
    // -----------------------------------------------------------------------

    private void createDocumentStructure(
            UUID projectId,
            SdocDocument doc,
            String filename,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        if (doc.sections().isEmpty()) {
            return;
        }

        try {
            UUID documentId = findOrCreateDocument(projectId, filename, counters);
            int sectionOrder = 0;
            for (SdocSection section : doc.sections()) {
                createSectionWithContent(documentId, section, sectionOrder++, uidToId, counters, errors);
            }
        } catch (ConflictException | NotFoundException | DomainValidationException e) {
            log.warn("import_document_failed: filename={} error={}", filename, e.getMessage());
            errors.add(new ImportError("documents", filename, e.getMessage(), null, null, null));
        }
    }

    private UUID findOrCreateDocument(UUID projectId, String filename, ImportCounters counters) {
        String docTitle = deriveDocumentTitle(filename);
        var existing = documentRepository.findByProjectIdAndTitle(projectId, docTitle);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        var created = documentService.create(
                new CreateDocumentCommand(projectId, docTitle, "1.0.0", "Imported from " + filename));
        counters.documentsCreated++;
        return created.getId();
    }

    private void createSectionWithContent(
            UUID documentId,
            SdocSection section,
            int sortOrder,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        try {
            var existingSection =
                    sectionRepository.findFirstByDocumentIdAndParentIdIsNullAndTitle(documentId, section.title());
            UUID sectionId;
            boolean sectionAlreadyExisted;
            if (existingSection.isPresent()) {
                sectionId = existingSection.get().getId();
                sectionAlreadyExisted = true;
            } else {
                var created = sectionService.create(
                        new CreateSectionCommand(documentId, null, section.title(), "", sortOrder));
                sectionId = created.getId();
                counters.sectionsCreated++;
                sectionAlreadyExisted = false;
            }

            if (!sectionAlreadyExisted) {
                int contentOrder = 0;
                for (SdocContentItem item : section.items()) {
                    createContentItem(sectionId, item, contentOrder++, uidToId, counters, errors);
                }
            }
        } catch (ConflictException | NotFoundException | DomainValidationException e) {
            log.warn("import_section_failed: title={} error={}", section.title(), e.getMessage());
            errors.add(new ImportError("sections", section.title(), e.getMessage(), null, null, null));
        }
    }

    private void createContentItem(
            UUID sectionId,
            SdocContentItem item,
            int sortOrder,
            Map<String, UUID> uidToId,
            ImportCounters counters,
            List<ImportError> errors) {
        try {
            switch (item) {
                case SdocContentItem.RequirementRef ref -> {
                    UUID reqId = uidToId.get(ref.uid());
                    if (reqId == null) {
                        errors.add(new ImportError(
                                "section_content",
                                ref.uid(),
                                "Requirement not found for section content",
                                null,
                                null,
                                null));
                        return;
                    }
                    sectionContentService.create(new CreateSectionContentCommand(
                            sectionId, ContentType.REQUIREMENT, reqId, null, sortOrder));
                    counters.sectionContentsCreated++;
                }
                case SdocContentItem.TextBlock tb -> {
                    sectionContentService.create(new CreateSectionContentCommand(
                            sectionId, ContentType.TEXT_BLOCK, null, tb.text(), sortOrder));
                    counters.sectionContentsCreated++;
                }
            }
        } catch (ConflictException | NotFoundException | DomainValidationException e) {
            log.warn("import_section_content_failed: error={}", e.getMessage());
            errors.add(new ImportError("section_content", null, e.getMessage(), null, null, null));
        }
    }

    private static String deriveDocumentTitle(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Imported Document";
        }
        String name = filename;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        return name;
    }

    // -----------------------------------------------------------------------
    // Audit and result
    // -----------------------------------------------------------------------

    private ImportResult saveAuditAndBuildResult(
            ImportSourceType sourceType,
            String filename,
            int parsedCount,
            ImportCounters counters,
            List<ImportError> errors) {
        var audit = new RequirementImport(sourceType);
        audit.setSourceFile(filename);
        audit.setStats(Map.of(
                "requirementsParsed", parsedCount,
                "requirementsCreated", counters.requirementsCreated,
                "requirementsUpdated", counters.requirementsUpdated,
                "relationsCreated", counters.relationsCreated,
                "relationsSkipped", counters.relationsSkipped,
                "traceabilityLinksCreated", counters.traceabilityLinksCreated,
                "traceabilityLinksSkipped", counters.traceabilityLinksSkipped,
                "documentsCreated", counters.documentsCreated,
                "sectionsCreated", counters.sectionsCreated,
                "sectionContentsCreated", counters.sectionContentsCreated));
        audit.setErrors(toAuditErrors(errors));
        var savedAudit = importRepository.save(audit);

        return new ImportResult(
                savedAudit.getId(),
                savedAudit.getImportedAt(),
                parsedCount,
                counters.requirementsCreated,
                counters.requirementsUpdated,
                counters.relationsCreated,
                counters.relationsSkipped,
                counters.traceabilityLinksCreated,
                counters.traceabilityLinksSkipped,
                counters.documentsCreated,
                counters.sectionsCreated,
                counters.sectionContentsCreated,
                errors);
    }

    private static List<Map<String, Object>> toAuditErrors(List<ImportError> errors) {
        return errors.stream()
                .map(e -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("phase", e.phase());
                    m.put("uid", e.uid());
                    m.put("error", e.error());
                    if (e.parent() != null) m.put("parent", e.parent());
                    if (e.target() != null) m.put("target", e.target());
                    if (e.issueRef() != null) m.put("issueRef", e.issueRef());
                    return (Map<String, Object>) m;
                })
                .toList();
    }
}
