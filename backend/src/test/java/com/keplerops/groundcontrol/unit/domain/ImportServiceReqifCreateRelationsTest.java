package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
class ImportServiceReqifCreateRelationsTest {
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
        void createsHierarchyRelations() {
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
                    .thenReturn(false);
            when(requirementService.createRelation(childId, parentId, RelationType.PARENT))
                    .thenReturn(new RequirementRelation(child, parent, RelationType.PARENT));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.relationsCreated()).isEqualTo(1);
            verify(requirementService).createRelation(childId, parentId, RelationType.PARENT);
        }

        @Test
        void createsExplicitSpecRelations() {
            String reqif = reqifWithExplicitRelation("RIF-SRC", "RIF-TGT", "depends on");
            UUID srcId = UUID.randomUUID();
            UUID tgtId = UUID.randomUUID();
            var src = makeRequirement("RIF-SRC", srcId);
            var tgt = makeRequirement("RIF-TGT", tgtId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-SRC"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-TGT"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenReturn(src)
                    .thenReturn(tgt);
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(srcId, tgtId, RelationType.DEPENDS_ON))
                    .thenReturn(false);
            when(requirementService.createRelation(srcId, tgtId, RelationType.DEPENDS_ON))
                    .thenReturn(new RequirementRelation(src, tgt, RelationType.DEPENDS_ON));
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
        void skipsExplicitRelationWhenHierarchyAlreadyCreatedIt() {
            // ReqIF with both hierarchy parent AND an explicit SpecRelation expressing the same
            // PARENT relationship — Phase 2 creates it from hierarchy, Phase 2b skips the duplicate.
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
                          <SPEC-TYPES>
                            <SPEC-RELATION-TYPE IDENTIFIER="srt-1" LONG-NAME="Parent Relationship"/>
                          </SPEC-TYPES>
                          <SPEC-OBJECTS>
                            <SPEC-OBJECT IDENTIFIER="RIF-PARENT" LONG-NAME="Parent"/>
                            <SPEC-OBJECT IDENTIFIER="RIF-CHILD" LONG-NAME="Child"/>
                          </SPEC-OBJECTS>
                          <SPEC-RELATIONS>
                            <SPEC-RELATION IDENTIFIER="rel-dup">
                              <TYPE><SPEC-RELATION-TYPE-REF>srt-1</SPEC-RELATION-TYPE-REF></TYPE>
                              <SOURCE><SOURCE-REF>RIF-CHILD</SOURCE-REF></SOURCE>
                              <TARGET><TARGET-REF>RIF-PARENT</TARGET-REF></TARGET>
                            </SPEC-RELATION>
                          </SPEC-RELATIONS>
                          <SPECIFICATIONS>
                            <SPECIFICATION IDENTIFIER="spec-1" LONG-NAME="Spec">
                              <CHILDREN>
                                <SPEC-HIERARCHY IDENTIFIER="sh-1">
                                  <OBJECT><OBJECT-REF>RIF-PARENT</OBJECT-REF></OBJECT>
                                  <CHILDREN>
                                    <SPEC-HIERARCHY IDENTIFIER="sh-2">
                                      <OBJECT><OBJECT-REF>RIF-CHILD</OBJECT-REF></OBJECT>
                                    </SPEC-HIERARCHY>
                                  </CHILDREN>
                                </SPEC-HIERARCHY>
                              </CHILDREN>
                            </SPECIFICATION>
                          </SPECIFICATIONS>
                        </REQ-IF-CONTENT>
                      </CORE-CONTENT>
                    </REQ-IF>
                    """;
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
            // First call (Phase 2 hierarchy): relation does not exist yet → create
            // Second call (Phase 2b explicit): relation already exists → skip
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(childId, parentId, RelationType.PARENT))
                    .thenReturn(false)
                    .thenReturn(true);
            when(requirementService.createRelation(childId, parentId, RelationType.PARENT))
                    .thenReturn(new RequirementRelation(child, parent, RelationType.PARENT));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.relationsCreated()).isEqualTo(1);
            assertThat(result.relationsSkipped()).isEqualTo(1);
        }

        @Test
        void lookupParentFromDb_whenNotInBatch_reqif() {
            // Parent creation fails in Phase 1 but exists in DB for Phase 2 fallback
            String reqifWithMissingParent =
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
                            <SPEC-OBJECT IDENTIFIER="RIF-PARENT-DB" LONG-NAME="Parent"/>
                            <SPEC-OBJECT IDENTIFIER="RIF-CHILD-DB" LONG-NAME="Child"/>
                          </SPEC-OBJECTS>
                          <SPEC-RELATIONS/>
                          <SPECIFICATIONS>
                            <SPECIFICATION IDENTIFIER="spec-1" LONG-NAME="Spec">
                              <CHILDREN>
                                <SPEC-HIERARCHY IDENTIFIER="sh-1">
                                  <OBJECT><OBJECT-REF>RIF-PARENT-DB</OBJECT-REF></OBJECT>
                                  <CHILDREN>
                                    <SPEC-HIERARCHY IDENTIFIER="sh-2">
                                      <OBJECT><OBJECT-REF>RIF-CHILD-DB</OBJECT-REF></OBJECT>
                                    </SPEC-HIERARCHY>
                                  </CHILDREN>
                                </SPEC-HIERARCHY>
                              </CHILDREN>
                            </SPECIFICATION>
                          </SPECIFICATIONS>
                        </REQ-IF-CONTENT>
                      </CORE-CONTENT>
                    </REQ-IF>
                    """;
            UUID childId = UUID.randomUUID();
            UUID parentId = UUID.randomUUID();
            var child = makeRequirement("RIF-CHILD-DB", childId);
            var parentReq = makeRequirement("RIF-PARENT-DB", parentId);

            // Phase 1: child is new, parent creation fails (simulating parent only in DB)
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-PARENT-DB"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-CHILD-DB"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenThrow(new DomainValidationException("Simulated failure"))
                    .thenReturn(child);
            // Phase 2: parent not in batch, so lookup from DB
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-PARENT-DB"))
                    .thenReturn(Optional.empty()) // Phase 1 call
                    .thenReturn(Optional.of(parentReq)); // Phase 2 DB fallback
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(childId, parentId, RelationType.PARENT))
                    .thenReturn(false);
            when(requirementService.createRelation(childId, parentId, RelationType.PARENT))
                    .thenReturn(new RequirementRelation(child, parentReq, RelationType.PARENT));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqifWithMissingParent);

            assertThat(result.relationsCreated()).isEqualTo(1);
            verify(requirementService).createRelation(childId, parentId, RelationType.PARENT);
        }

        @Test
        void parentNotFoundAnywhere_collectsError_reqif() {
            String reqif = reqifWithHierarchy("RIF-PARENT-MISS", "Parent", "RIF-CHILD-MISS", "Child");
            UUID childId = UUID.randomUUID();
            var child = makeRequirement("RIF-CHILD-MISS", childId);

            // Only child gets created; parent creation fails
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-PARENT-MISS"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-CHILD-MISS"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenThrow(new DomainValidationException("Simulated failure"))
                    .thenReturn(child);
            // Phase 2: parent not in batch and not in DB
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-PARENT-MISS"))
                    .thenReturn(Optional.empty());
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.relationsCreated()).isZero();
            // One error from Phase 1 (parent create failed) + one from Phase 2 (parent not found)
            assertThat(result.errors().stream()
                            .filter(e -> e.error().contains("Parent not found"))
                            .count())
                    .isEqualTo(1);
        }

        @Test
        void relationCreationError_collectsError_reqif() {
            String reqif = reqifWithHierarchy("RIF-PARENT-ERR", "Parent", "RIF-CHILD-ERR", "Child");
            UUID parentId = UUID.randomUUID();
            UUID childId = UUID.randomUUID();
            var parent = makeRequirement("RIF-PARENT-ERR", parentId);
            var child = makeRequirement("RIF-CHILD-ERR", childId);

            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-PARENT-ERR"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-CHILD-ERR"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenReturn(parent)
                    .thenReturn(child);
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(childId, parentId, RelationType.PARENT))
                    .thenReturn(false);
            when(requirementService.createRelation(childId, parentId, RelationType.PARENT))
                    .thenThrow(new DomainValidationException("Simulated relation failure"));
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.relationsCreated()).isZero();
            assertThat(result.errors())
                    .anyMatch(e -> e.phase().equals("relations") && e.error().contains("Simulated relation failure"));
        }

        @Test
        void sourceNotFoundForExplicitRelation_collectsError() {
            String reqif = reqifWithExplicitRelation("RIF-MISSING-SRC", "RIF-TGT-OK", "depends on");
            UUID tgtId = UUID.randomUUID();
            var tgt = makeRequirement("RIF-TGT-OK", tgtId);

            // Only target gets created; source creation fails
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-MISSING-SRC"))
                    .thenReturn(Optional.empty());
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "RIF-TGT-OK"))
                    .thenReturn(Optional.empty());
            when(requirementService.create(any(CreateRequirementCommand.class)))
                    .thenThrow(new DomainValidationException("Simulated failure"))
                    .thenReturn(tgt);
            when(importRepository.save(any(RequirementImport.class))).thenAnswer(inv -> {
                var audit = inv.<RequirementImport>getArgument(0);
                setField(audit, "id", UUID.randomUUID());
                return audit;
            });

            ImportResult result = service.importReqif(PROJECT_ID, "test.reqif", reqif);

            assertThat(result.errors()).anyMatch(e -> e.error().contains("Source not found"));
        }
    }
}
