package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementImportRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.ImportResult;
import com.keplerops.groundcontrol.domain.requirements.service.ImportService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
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
class ImportServiceReqifCreateRelations2Test {
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

    private static String reqifWithHierarchy(String parentId, String parentTitle, String childId, String childTitle) {
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
                        <SPEC-OBJECT IDENTIFIER="%s" LONG-NAME="%s"/>
                      </SPEC-OBJECTS>
                      <SPEC-RELATIONS/>
                      <SPECIFICATIONS>
                        <SPECIFICATION IDENTIFIER="spec-1" LONG-NAME="Test Spec">
                          <CHILDREN>
                            <SPEC-HIERARCHY IDENTIFIER="sh-1">
                              <OBJECT><OBJECT-REF>%s</OBJECT-REF></OBJECT>
                              <CHILDREN>
                                <SPEC-HIERARCHY IDENTIFIER="sh-2">
                                  <OBJECT><OBJECT-REF>%s</OBJECT-REF></OBJECT>
                                </SPEC-HIERARCHY>
                              </CHILDREN>
                            </SPEC-HIERARCHY>
                          </CHILDREN>
                        </SPECIFICATION>
                      </SPECIFICATIONS>
                    </REQ-IF-CONTENT>
                  </CORE-CONTENT>
                </REQ-IF>
                """
                .formatted(parentId, parentTitle, childId, childTitle, parentId, childId);
    }

    private static String reqifWithExplicitRelation(String sourceId, String targetId, String relTypeName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <REQ-IF xmlns="http://www.omg.org/spec/ReqIF/20110401/reqif.xsd">
                  <THE-HEADER>
                    <REQ-IF-HEADER IDENTIFIER="h1"><TITLE>Test</TITLE></REQ-IF-HEADER>
                  </THE-HEADER>
                  <CORE-CONTENT>
                    <REQ-IF-CONTENT>
                      <DATATYPES/>
                      <SPEC-TYPES>
                        <SPEC-RELATION-TYPE IDENTIFIER="srt-1" LONG-NAME="%s"/>
                      </SPEC-TYPES>
                      <SPEC-OBJECTS>
                        <SPEC-OBJECT IDENTIFIER="%s" LONG-NAME="Source"/>
                        <SPEC-OBJECT IDENTIFIER="%s" LONG-NAME="Target"/>
                      </SPEC-OBJECTS>
                      <SPEC-RELATIONS>
                        <SPEC-RELATION IDENTIFIER="rel-1">
                          <TYPE><SPEC-RELATION-TYPE-REF>srt-1</SPEC-RELATION-TYPE-REF></TYPE>
                          <SOURCE><SOURCE-REF>%s</SOURCE-REF></SOURCE>
                          <TARGET><TARGET-REF>%s</TARGET-REF></TARGET>
                        </SPEC-RELATION>
                      </SPEC-RELATIONS>
                      <SPECIFICATIONS/>
                    </REQ-IF-CONTENT>
                  </CORE-CONTENT>
                </REQ-IF>
                """
                .formatted(relTypeName, sourceId, targetId, sourceId, targetId);
    }

    @Nested
    class ReqifCreateRelations {

        @Test
        void targetNotFoundForExplicitRelation_collectsError() {
            String reqif = reqifWithExplicitRelation("RIF-SRC-OK", "RIF-MISSING-TGT", "depends on");
            UUID srcId = UUID.randomUUID();
            var src = makeRequirement("RIF-SRC-OK", srcId);

            // Only source gets created; target creation fails
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-SRC-OK"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-MISSING-TGT"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenReturn(src)
                    .thenThrow(new DomainValidationException("Simulated failure"));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.errors()).anyMatch(e -> e.error().contains("Target not found"));
        }

        @Test
        void explicitRelationSourceLookedUpFromDb() {
            String reqif = reqifWithExplicitRelation("RIF-DB-SRC", "RIF-BATCH-TGT", "depends on");
            UUID srcId = UUID.randomUUID();
            UUID tgtId = UUID.randomUUID();
            var srcReq = makeRequirement("RIF-DB-SRC", srcId);
            var tgtReq = makeRequirement("RIF-BATCH-TGT", tgtId);

            // Source creation fails, target succeeds
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-DB-SRC"))
                    .thenReturn(Optional.empty()) // Phase 1
                    .thenReturn(Optional.of(srcReq)); // Phase 2b DB fallback
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-BATCH-TGT"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenThrow(new DomainValidationException("Simulated failure"))
                    .thenReturn(tgtReq);
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(srcId, tgtId, RelationType.DEPENDS_ON))
                    .thenReturn(false);
            when(requirementService.createRelation(srcId, tgtId, RelationType.DEPENDS_ON))
                    .thenReturn(new RequirementRelation(srcReq, tgtReq, RelationType.DEPENDS_ON));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.relationsCreated()).isEqualTo(1);
            verify(requirementService).createRelation(srcId, tgtId, RelationType.DEPENDS_ON);
        }

        @Test
        void explicitRelationCreationError_collectsError() {
            String reqif = reqifWithExplicitRelation("RIF-SRC-REL", "RIF-TGT-REL", "depends on");
            UUID srcId = UUID.randomUUID();
            UUID tgtId = UUID.randomUUID();
            var src = makeRequirement("RIF-SRC-REL", srcId);
            var tgt = makeRequirement("RIF-TGT-REL", tgtId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-SRC-REL"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-TGT-REL"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenReturn(src)
                    .thenReturn(tgt);
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(srcId, tgtId, RelationType.DEPENDS_ON))
                    .thenReturn(false);
            when(requirementService.createRelation(srcId, tgtId, RelationType.DEPENDS_ON))
                    .thenThrow(new DomainValidationException("Simulated SpecRelation failure"));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.relationsCreated()).isZero();
            assertThat(result.errors())
                    .anyMatch(
                            e -> e.phase().equals("relations") && e.error().contains("Simulated SpecRelation failure"));
        }

        @Test
        void skipsExistingRelationsFromReqif() {
            String reqif = reqifWithHierarchy("RIF-PARENT", "Parent", "RIF-CHILD", "Child");
            UUID parentId = UUID.randomUUID();
            UUID childId = UUID.randomUUID();
            var parent = makeRequirement("RIF-PARENT", parentId);
            var child = makeRequirement("RIF-CHILD", childId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-PARENT"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-CHILD"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenReturn(parent)
                    .thenReturn(child);
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(childId, parentId, RelationType.PARENT))
                    .thenReturn(true);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.relationsSkipped()).isEqualTo(1);
            assertThat(result.relationsCreated()).isZero();
            verify(requirementService, never()).createRelation(any(), any(), any());
        }
    }

    @Nested
    class ReqifErrorHandling {

        @Test
        void collectsErrorsAndContinuesForReqif() {
            // Two requirements: first one throws, second succeeds
            String reqif =
                    """
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
                            <SPEC-OBJECT IDENTIFIER="RIF-FAIL" LONG-NAME="Fail"/>
                            <SPEC-OBJECT IDENTIFIER="RIF-OK" LONG-NAME="OK"/>
                          </SPEC-OBJECTS>
                          <SPEC-RELATIONS/>
                          <SPECIFICATIONS/>
                        </REQ-IF-CONTENT>
                      </CORE-CONTENT>
                    </REQ-IF>
                    """;
            UUID okId = UUID.randomUUID();
            var okReq = makeRequirement("RIF-OK", okId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-FAIL"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-OK"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenThrow(new DomainValidationException("Simulated failure"))
                    .thenReturn(okReq);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.requirementsCreated()).isEqualTo(1);
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).uid()).isEqualTo("RIF-FAIL");
        }
    }

    @Nested
    class ReqifAuditRecord {

        @Test
        void savesReqifImportAuditRecord() {
            String reqif = minimalReqif("RIF-AUDIT", "Audit Test");
            UUID reqId = UUID.randomUUID();
            var req = makeRequirement("RIF-AUDIT", reqId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-AUDIT"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class))).thenReturn(req);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            service.importReqif(PROJECT_ID, "test.reqif", reqif);

            verify(importRepository).save(any(RequirementImport.class));
        }
    }
}
