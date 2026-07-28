package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.documents.model.Document;
import com.keplerops.groundcontrol.domain.documents.model.Section;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.documents.repository.SectionRepository;
import com.keplerops.groundcontrol.domain.documents.service.CreateDocumentCommand;
import com.keplerops.groundcontrol.domain.documents.service.CreateSectionCommand;
import com.keplerops.groundcontrol.domain.documents.service.CreateSectionContentCommand;
import com.keplerops.groundcontrol.domain.documents.service.DocumentService;
import com.keplerops.groundcontrol.domain.documents.service.SectionContentService;
import com.keplerops.groundcontrol.domain.documents.service.SectionService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementImport;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementImportRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.ImportResult;
import com.keplerops.groundcontrol.domain.requirements.service.ImportService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from ImportServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class ImportServiceDocumentStructureTest {
    @Mock
    private RequirementService requirementService;

    @Mock
    private TraceabilityService traceabilityService;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementRelationRepository relationRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Mock
    private RequirementImportRepository importRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private SectionService sectionService;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private SectionContentService sectionContentService;

    private ImportService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    @BeforeEach
    void setUp() {
        service = new ImportService(
                requirementService,
                traceabilityService,
                requirementRepository,
                relationRepository,
                traceabilityLinkRepository,
                importRepository,
                documentService,
                documentRepository,
                sectionService,
                sectionRepository,
                sectionContentService);
    }

    private static Requirement makeRequirement(String uid, UUID id) {
        var req = new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", id);
        return req;
    }

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
    }

    @Nested
    class DocumentStructure {

        private static String sdocWithSection() {
            return """
                    [[SECTION]]
                    TITLE: Wave 1 — Foundation

                    [TEXT]
                    Introduction text.

                    [REQUIREMENT]
                    UID: DOC-001
                    TITLE: First
                    STATEMENT: >>>
                    Statement.
                    <<<

                    [[/SECTION]]
                    """;
        }

        private void stubCommonMocks(UUID reqId) {
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "DOC-001"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenReturn(makeRequirement("DOC-001", reqId));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });
        }

        @Test
        void createsDocumentAndSectionFromSdoc() {
            UUID reqId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID sectionId = UUID.randomUUID();
            stubCommonMocks(reqId);

            var mockDoc = new Document(TEST_PROJECT, "my-doc", "1.0.0", "", "");
            setField(mockDoc, "id", docId);
            when(documentRepository.findByProjectIdAndTitle(PROJECT_ID, "my-doc"))
                    .thenReturn(Optional.empty());
            when(documentService.create(any(CreateDocumentCommand.class))).thenReturn(mockDoc);

            var mockSection = new Section(mockDoc, null, "Wave 1 — Foundation", "", 0);
            setField(mockSection, "id", sectionId);
            when(sectionRepository.findFirstByDocumentIdAndParentIdIsNullAndTitle(eq(docId), any()))
                    .thenReturn(Optional.empty());
            when(sectionService.create(any(CreateSectionCommand.class))).thenReturn(mockSection);

            ImportResult result = service.importStrictdoc(PROJECT_ID, "my-doc.sdoc", sdocWithSection());

            assertThat(result.documentsCreated()).isEqualTo(1);
            assertThat(result.sectionsCreated()).isEqualTo(1);
            // 1 text block + 1 requirement ref = 2 content items
            assertThat(result.sectionContentsCreated()).isEqualTo(2);
            verify(documentService).create(any(CreateDocumentCommand.class));
            verify(sectionService).create(any(CreateSectionCommand.class));
            verify(sectionContentService, times(2)).create(any(CreateSectionContentCommand.class));
        }

        @Test
        void skipsExistingDocumentOnReimport() {
            UUID reqId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID sectionId = UUID.randomUUID();
            stubCommonMocks(reqId);

            var mockDoc = new Document(TEST_PROJECT, "my-doc", "1.0.0", "", "");
            setField(mockDoc, "id", docId);
            when(documentRepository.findByProjectIdAndTitle(PROJECT_ID, "my-doc"))
                    .thenReturn(Optional.of(mockDoc));

            var mockSection = new Section(mockDoc, null, "Wave 1 — Foundation", "", 0);
            setField(mockSection, "id", sectionId);
            when(sectionRepository.findFirstByDocumentIdAndParentIdIsNullAndTitle(eq(docId), any()))
                    .thenReturn(Optional.of(mockSection));

            ImportResult result = service.importStrictdoc(PROJECT_ID, "my-doc.sdoc", sdocWithSection());

            assertThat(result.documentsCreated()).isZero();
            assertThat(result.sectionsCreated()).isZero();
            assertThat(result.sectionContentsCreated()).isZero();
            verify(documentService, never()).create(any(CreateDocumentCommand.class));
            verify(sectionContentService, never()).create(any(CreateSectionContentCommand.class));
        }

        @Test
        void reportsDocumentCountersInResult() {
            UUID reqId = UUID.randomUUID();
            UUID docId = UUID.randomUUID();
            UUID sectionId = UUID.randomUUID();
            stubCommonMocks(reqId);

            var mockDoc = new Document(TEST_PROJECT, "my-doc", "1.0.0", "", "");
            setField(mockDoc, "id", docId);
            when(documentRepository.findByProjectIdAndTitle(PROJECT_ID, "my-doc"))
                    .thenReturn(Optional.empty());
            when(documentService.create(any(CreateDocumentCommand.class))).thenReturn(mockDoc);

            var mockSection = new Section(mockDoc, null, "Wave 1 — Foundation", "", 0);
            setField(mockSection, "id", sectionId);
            when(sectionRepository.findFirstByDocumentIdAndParentIdIsNullAndTitle(eq(docId), any()))
                    .thenReturn(Optional.empty());
            when(sectionService.create(any(CreateSectionCommand.class))).thenReturn(mockSection);

            ImportResult result = service.importStrictdoc(PROJECT_ID, "my-doc.sdoc", sdocWithSection());

            assertThat(result.requirementsCreated()).isEqualTo(1);
            assertThat(result.documentsCreated()).isEqualTo(1);
            assertThat(result.sectionsCreated()).isEqualTo(1);
            assertThat(result.sectionContentsCreated()).isEqualTo(2);
        }
    }
}
