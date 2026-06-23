package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.documents.model.Document;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.documents.service.DocumentExportHtmlService;
import com.keplerops.groundcontrol.domain.documents.service.DocumentExportPdfService;
import com.keplerops.groundcontrol.domain.documents.service.DocumentExportReqifService;
import com.keplerops.groundcontrol.domain.documents.service.DocumentExportSdocService;
import com.keplerops.groundcontrol.domain.documents.service.DocumentExportService;
import com.keplerops.groundcontrol.domain.documents.service.DocumentReadingOrder;
import com.keplerops.groundcontrol.domain.documents.service.DocumentReadingOrderService;
import com.keplerops.groundcontrol.domain.documents.service.ReadingOrderContentItem;
import com.keplerops.groundcontrol.domain.documents.service.ReadingOrderNode;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentExportServiceTest {

    @Mock
    private DocumentReadingOrderService readingOrderService;

    @Mock
    private DocumentExportSdocService sdocService;

    @Mock
    private DocumentExportHtmlService htmlService;

    @Mock
    private DocumentExportPdfService pdfService;

    @Mock
    private DocumentExportReqifService reqifService;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementRelationRepository relationRepository;

    private DocumentExportService service;

    private static final UUID DOC_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID REQ_ID = UUID.randomUUID();
    private static final UUID PARENT_REQ_ID = UUID.randomUUID();
    private static final Project TEST_PROJECT_A = makeProject(PROJECT_ID_A, "proj-a");

    @BeforeEach
    void setUp() {
        service = new DocumentExportService(
                readingOrderService,
                sdocService,
                htmlService,
                pdfService,
                reqifService,
                documentRepository,
                requirementRepository,
                relationRepository);
    }

    // --- helpers ---

    private static Project makeProject(UUID projectId, String identifier) {
        var p = new Project(identifier, identifier);
        setField(p, "id", projectId);
        return p;
    }

    private static Document makeDocument(UUID docId, Project project) {
        var d = new Document(project, "Test Doc", "1.0", "", null);
        setField(d, "id", docId);
        return d;
    }

    private static Requirement makeRequirement(String uid, UUID id) {
        var req = new Requirement(TEST_PROJECT_A, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", id);
        return req;
    }

    private static Requirement makeRequirement(Project project, String uid) {
        var req = new Requirement(project, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", UUID.randomUUID());
        return req;
    }

    // --- delegation tests ---

    @Test
    void exportToSdoc_delegatesToSdocService() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of());
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);
        when(sdocService.toSdoc(eq(order), any())).thenReturn("[[SECTION]]\n");

        String result = service.exportToSdoc(DOC_ID);

        assertThat(result).isEqualTo("[[SECTION]]\n");
        verify(readingOrderService).getReadingOrder(DOC_ID);
        verify(sdocService).toSdoc(eq(order), any());
    }

    @Test
    void exportToHtml_delegatesToHtmlService() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of());
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);
        when(htmlService.toHtml(eq(order), any())).thenReturn("<html></html>");

        String result = service.exportToHtml(DOC_ID);

        assertThat(result).isEqualTo("<html></html>");
        verify(htmlService).toHtml(eq(order), any());
    }

    @Test
    void exportToPdf_delegatesToPdfService() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of());
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);
        byte[] pdfBytes = new byte[] {0x25, 0x50, 0x44, 0x46};
        when(pdfService.toPdf(eq(order), any())).thenReturn(pdfBytes);

        byte[] result = service.exportToPdf(DOC_ID);

        assertThat(result).isEqualTo(pdfBytes);
        verify(pdfService).toPdf(eq(order), any());
    }

    @Test
    void exportToReqif_delegatesToReqifService() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of());
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);
        when(reqifService.toReqif(eq(order), any())).thenReturn("<?xml version=\"1.0\"?><REQ-IF/>");

        String result = service.exportToReqif(DOC_ID);

        assertThat(result).isEqualTo("<?xml version=\"1.0\"?><REQ-IF/>");
        verify(reqifService).toReqif(eq(order), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportToSdoc_nonParentRelationsAreExcludedFromParentUids() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var content = List.of(new ReadingOrderContentItem("REQUIREMENT", "REQ-001", "Title", null, 0));
        var section = new ReadingOrderNode(UUID.randomUUID(), "Section", "", 0, content, List.of());
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of(section));
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);

        var req = makeRequirement("REQ-001", REQ_ID);
        var otherReq = makeRequirement("OTHER-001", PARENT_REQ_ID);
        when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "REQ-001"))
                .thenReturn(Optional.of(req));
        // DEPENDS_ON is not PARENT, so should not appear in parentUids
        var relation = new RequirementRelation(req, otherReq, RelationType.DEPENDS_ON);
        when(relationRepository.findBySourceId(REQ_ID)).thenReturn(List.of(relation));

        var captor = ArgumentCaptor.forClass(Map.class);
        when(sdocService.toSdoc(any(), captor.capture())).thenReturn("");

        service.exportToSdoc(DOC_ID);

        var exportData = (com.keplerops.groundcontrol.domain.documents.service.RequirementExportData)
                captor.getValue().get("REQ-001");
        assertThat(exportData).isNotNull();
        assertThat(exportData.parentUids()).isEmpty();
    }

    @Test
    void exportToSdoc_collectsRequirementUidsAndFetchesDataScopedToProject() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var content = List.of(new ReadingOrderContentItem("REQUIREMENT", "REQ-001", "Title", null, 0));
        var section = new ReadingOrderNode(UUID.randomUUID(), "Section", "", 0, content, List.of());
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of(section));
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);

        var req = makeRequirement("REQ-001", REQ_ID);
        when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "REQ-001"))
                .thenReturn(Optional.of(req));
        when(relationRepository.findBySourceId(REQ_ID)).thenReturn(List.of());
        when(sdocService.toSdoc(any(), any())).thenReturn("");

        service.exportToSdoc(DOC_ID);

        verify(requirementRepository).findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "REQ-001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportToSdoc_includesParentRelations() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var content = List.of(new ReadingOrderContentItem("REQUIREMENT", "CHILD-001", "Child", null, 0));
        var section = new ReadingOrderNode(UUID.randomUUID(), "Section", "", 0, content, List.of());
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of(section));
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);

        var childReq = makeRequirement("CHILD-001", REQ_ID);
        var parentReq = makeRequirement("PARENT-001", PARENT_REQ_ID);
        when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "CHILD-001"))
                .thenReturn(Optional.of(childReq));

        var relation = new RequirementRelation(childReq, parentReq, RelationType.PARENT);
        when(relationRepository.findBySourceId(REQ_ID)).thenReturn(List.of(relation));

        var captor = ArgumentCaptor.forClass(Map.class);
        when(sdocService.toSdoc(any(), captor.capture())).thenReturn("");

        service.exportToSdoc(DOC_ID);

        Map<String, ?> reqMap = captor.getValue();
        assertThat(reqMap).containsKey("CHILD-001");
    }

    @Test
    void exportToSdoc_skipsTextBlocksInUidCollection() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var textContent = new ReadingOrderContentItem("TEXT_BLOCK", null, null, "Some text", 0);
        var reqContent = new ReadingOrderContentItem("REQUIREMENT", "REQ-002", "Title", null, 1);
        var section =
                new ReadingOrderNode(UUID.randomUUID(), "Section", "", 0, List.of(textContent, reqContent), List.of());
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of(section));
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);

        var req = makeRequirement("REQ-002", REQ_ID);
        when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "REQ-002"))
                .thenReturn(Optional.of(req));
        when(relationRepository.findBySourceId(REQ_ID)).thenReturn(List.of());
        when(sdocService.toSdoc(any(), any())).thenReturn("");

        service.exportToSdoc(DOC_ID);

        verify(requirementRepository).findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "REQ-002");
    }

    /**
     * Regression: two projects share the same UID. Export for project A must not bind the
     * requirement from project B.
     */
    @Test
    @SuppressWarnings("unchecked")
    void exportToSdoc_duplicateUidAcrossProjects_scopedToOwningProject() {
        Project projectA = TEST_PROJECT_A;
        Project projectB = makeProject(PROJECT_ID_B, "proj-b");

        var docA = makeDocument(DOC_ID, projectA);
        UUID docBId = UUID.randomUUID();
        var docB = makeDocument(docBId, projectB);

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(docA));
        when(documentRepository.findById(docBId)).thenReturn(Optional.of(docB));

        var reqInA = makeRequirement(projectA, "SHARED-001");
        var reqInB = makeRequirement(projectB, "SHARED-001");

        when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "SHARED-001"))
                .thenReturn(Optional.of(reqInA));
        when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID_B, "SHARED-001"))
                .thenReturn(Optional.of(reqInB));

        var content = List.of(new ReadingOrderContentItem("REQUIREMENT", "SHARED-001", "Shared", null, 0));
        var section = new ReadingOrderNode(UUID.randomUUID(), "Section", "", 0, content, List.of());
        var orderA = new DocumentReadingOrder(DOC_ID, "Doc A", "1.0", "", List.of(section));
        var orderB = new DocumentReadingOrder(docBId, "Doc B", "1.0", "", List.of(section));
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(orderA);
        when(readingOrderService.getReadingOrder(docBId)).thenReturn(orderB);
        when(relationRepository.findBySourceId(any())).thenReturn(List.of());

        var captorA = ArgumentCaptor.forClass(Map.class);
        var captorB = ArgumentCaptor.forClass(Map.class);
        when(sdocService.toSdoc(eq(orderA), captorA.capture())).thenReturn("");
        when(sdocService.toSdoc(eq(orderB), captorB.capture())).thenReturn("");

        service.exportToSdoc(DOC_ID);
        service.exportToSdoc(docBId);

        var exportDataA = captorA.getValue().get("SHARED-001");
        var exportDataB = captorB.getValue().get("SHARED-001");
        assertThat(exportDataA).isNotNull();
        assertThat(exportDataB).isNotNull();
        // Both resolve to something — the key guard is that the lookup is project-scoped
        verify(requirementRepository).findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "SHARED-001");
        verify(requirementRepository).findByProjectIdAndUidIgnoreCase(PROJECT_ID_B, "SHARED-001");
    }

    /** Project-scoped UID lookup must always be used; global lookup by UID is not available. */
    @Test
    void exportToSdoc_usesProjectScopedUidLookup() {
        var doc = makeDocument(DOC_ID, TEST_PROJECT_A);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        var content = List.of(new ReadingOrderContentItem("REQUIREMENT", "REQ-001", "Title", null, 0));
        var section = new ReadingOrderNode(UUID.randomUUID(), "Section", "", 0, content, List.of());
        var order = new DocumentReadingOrder(DOC_ID, "Test Doc", "1.0", "", List.of(section));
        when(readingOrderService.getReadingOrder(DOC_ID)).thenReturn(order);
        when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "REQ-001"))
                .thenReturn(Optional.empty());
        when(sdocService.toSdoc(any(), any())).thenReturn("");

        service.exportToSdoc(DOC_ID);

        verify(requirementRepository).findByProjectIdAndUidIgnoreCase(PROJECT_ID_A, "REQ-001");
    }
}
