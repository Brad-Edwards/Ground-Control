package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.documents.repository.SectionRepository;
import com.keplerops.groundcontrol.domain.documents.service.DocumentService;
import com.keplerops.groundcontrol.domain.documents.service.SectionContentService;
import com.keplerops.groundcontrol.domain.documents.service.SectionService;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
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
import com.keplerops.groundcontrol.domain.requirements.service.UpdateRequirementCommand;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from ImportServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class ImportServiceErrorHandlingTest {
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

    @InjectMocks
    private ImportService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    private static Requirement makeRequirement(String uid, UUID id) {
        var req = new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", id);
        return req;
    }

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
    }

    private static String minimalSdoc(String uid) {
        return """
                [REQUIREMENT]
                UID: %s
                TITLE: Test
                STATEMENT: >>>
                Statement.
                <<<
                """
                .formatted(uid);
    }

    // =====================================================================
    // ReqIF import tests
    // =====================================================================

    private static String minimalReqif(String identifier, String title) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <REQ-IF xmlns="http://www.omg.org/spec/ReqIF/20110401/reqif.xsd">
                  <THE-HEADER>
                    <REQ-IF-HEADER IDENTIFIER="h1"><TITLE>Test</TITLE></REQ-IF-HEADER>
                  </THE-HEADER>
                  <CORE-CONTENT>
                    <REQ-IF-CONTENT>
                      <DATATYPES/>
                      <SPEC-TYPES/>
                      <SPEC-OBJECTS>
                        <SPEC-OBJECT IDENTIFIER="%s" LONG-NAME="%s"/>
                      </SPEC-OBJECTS>
                      <SPEC-RELATIONS/>
                      <SPECIFICATIONS/>
                    </REQ-IF-CONTENT>
                  </CORE-CONTENT>
                </REQ-IF>
                """
                .formatted(identifier, title);
    }

    @Nested
    class ErrorHandling {

        @Test
        void collectsErrorsAndContinues() {
            // Two requirements: first one throws, second succeeds
            String sdoc =
                    """
                    [REQUIREMENT]
                    UID: REQ-FAIL
                    TITLE: Fail
                    STATEMENT: >>>
                    Fails.
                    <<<

                    [REQUIREMENT]
                    UID: REQ-OK
                    TITLE: OK
                    STATEMENT: >>>
                    Succeeds.
                    <<<
                    """;
            UUID okId = UUID.randomUUID();
            var okReq = makeRequirement("REQ-OK", okId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-FAIL"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-OK"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenThrow(new DomainValidationException("Simulated failure"))
                    .thenReturn(okReq);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            var result = service.importStrictdoc(PROJECT_ID, "test.sdoc", sdoc);

            assertThat(result.requirementsCreated()).isEqualTo(1);
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).uid()).isEqualTo("REQ-FAIL");
        }
    }

    @Nested
    class AuditRecord {

        @Test
        void savesImportAuditRecord() {
            String sdoc = minimalSdoc("REQ-AUDIT");
            UUID reqId = UUID.randomUUID();
            var req = makeRequirement("REQ-AUDIT", reqId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-AUDIT"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class))).thenReturn(req);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            service.importStrictdoc(PROJECT_ID, "test.sdoc", sdoc);

            verify(importRepository).save(any(RequirementImport.class));
        }
    }

    @Nested
    class ReqifUpsertRequirements {

        @Test
        void createsNewRequirementsFromReqif() {
            String reqif = minimalReqif("RIF-NEW", "New Requirement");
            UUID newId = UUID.randomUUID();
            var created = makeRequirement("RIF-NEW", newId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-NEW"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class))).thenReturn(created);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.requirementsCreated()).isEqualTo(1);
            assertThat(result.requirementsUpdated()).isZero();
            verify(requirementService).create(any(CreateRequirementCommand.class));
        }

        @Test
        void updatesExistingRequirementsFromReqif() {
            String reqif = minimalReqif("RIF-EXISTING", "Updated");
            UUID existingId = UUID.randomUUID();
            var existing = makeRequirement("RIF-EXISTING", existingId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-EXISTING"))
                    .thenReturn(Optional.of(existing));
            when(requirementService.update(eq(existingId), any(UpdateRequirementCommand.class)))
                    .thenReturn(existing);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.requirementsUpdated()).isEqualTo(1);
            assertThat(result.requirementsCreated()).isZero();
        }
    }
}
