package com.keplerops.groundcontrol.domain.requirements.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.audit.GroundControlRevisionEntity;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.ChangeCategory;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditQuery;
import org.hibernate.envers.query.AuditQueryCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from AuditServiceDiffTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class AuditServiceDiffTimelineAndHistoryDiffComputationTest {
    private static final UUID REQ_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementRelationRepository relationRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private AuditReader auditReader;

    private AuditService service;

    private static void setField(Object obj, String fieldName, Object value) {
        com.keplerops.groundcontrol.TestUtil.setField(obj, fieldName, value);
    }

    private static Requirement makeRequirement(String uid, String title) {
        var project = new Project("test", "Test");
        setField(project, "id", UUID.randomUUID());
        var req = new Requirement(project, uid, title, "Statement");
        setField(req, "id", REQ_ID);
        return req;
    }

    private static Requirement makeRequirementWithId(String uid, UUID id) {
        var project = new Project("test", "Test");
        setField(project, "id", UUID.randomUUID());
        var req = new Requirement(project, uid, uid, "Statement");
        setField(req, "id", id);
        return req;
    }

    @BeforeEach
    void setUp() {
        service =
                new AuditService(requirementRepository, relationRepository, traceabilityLinkRepository, entityManager);
    }

    @Nested
    class TimelineAndHistoryDiffComputation {

        private GroundControlRevisionEntity rev(int id, String actor) {
            var r = new GroundControlRevisionEntity();
            setField(r, "id", id);
            setField(r, "timestamp", id * 1000L);
            r.setActor(actor);
            return r;
        }

        private void stubEnversQuery(Class<?> entityClass, List<Object[]> rows) {
            var creator = mock(AuditQueryCreator.class);
            var query = mock(AuditQuery.class, Answers.RETURNS_SELF);
            when(auditReader.createQuery()).thenReturn(creator);
            when(creator.forRevisionsOfEntity(entityClass, false, true)).thenReturn(query);
            when(query.getResultList()).thenReturn(rows);
        }

        private TimelineEntry byType(List<TimelineEntry> entries, String revisionType) {
            return entries.stream()
                    .filter(e -> e.revisionType().equals(revisionType))
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        void getRequirementHistory_computesAddModDelDiffs() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);
            List<Object[]> rows = List.of(
                    new Object[] {makeRequirement("REQ-001", "Title 1"), rev(1, "alice"), RevisionType.ADD},
                    new Object[] {makeRequirement("REQ-001", "Title 2"), rev(2, "bob"), RevisionType.MOD},
                    new Object[] {makeRequirement("REQ-001", "Title 2"), rev(3, "carol"), RevisionType.DEL});

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                stubEnversQuery(Requirement.class, rows);

                var history = service.getRequirementHistory(REQ_ID);

                assertThat(history).hasSize(3);
                assertThat(history.get(0).revisionType()).isEqualTo("ADD");
                assertThat(history.get(0).changes().get("title").oldValue()).isNull();
                assertThat(history.get(0).changes().get("title").newValue()).isEqualTo("Title 1");
                assertThat(history.get(1).revisionType()).isEqualTo("MOD");
                assertThat(history.get(1).changes().get("title").oldValue()).isEqualTo("Title 1");
                assertThat(history.get(1).changes().get("title").newValue()).isEqualTo("Title 2");
                assertThat(history.get(2).revisionType()).isEqualTo("DEL");
                assertThat(history.get(2).changes().get("title").oldValue()).isEqualTo("Title 2");
                assertThat(history.get(2).changes().get("title").newValue()).isNull();
            }
        }

        @Test
        void timeline_requirementCategory_computesAddModDelDiffs() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);
            List<Object[]> rows = List.of(
                    new Object[] {makeRequirement("REQ-001", "Title 1"), rev(1, "alice"), RevisionType.ADD},
                    new Object[] {makeRequirement("REQ-001", "Title 2"), rev(2, "bob"), RevisionType.MOD},
                    new Object[] {makeRequirement("REQ-001", "Title 2"), rev(3, "carol"), RevisionType.DEL});

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                stubEnversQuery(Requirement.class, rows);

                var timeline =
                        service.getRequirementTimeline(REQ_ID, ChangeCategory.REQUIREMENT, null, null, null, 100, 0);

                assertThat(timeline).hasSize(3);
                assertThat(byType(timeline, "ADD").changes().get("title").newValue())
                        .isEqualTo("Title 1");
                assertThat(byType(timeline, "MOD").changes().get("title").oldValue())
                        .isEqualTo("Title 1");
                assertThat(byType(timeline, "MOD").changes().get("title").newValue())
                        .isEqualTo("Title 2");
                assertThat(byType(timeline, "DEL").changes().get("title").oldValue())
                        .isEqualTo("Title 2");
                assertThat(byType(timeline, "DEL").changes().get("title").newValue())
                        .isNull();
            }
        }

        @Test
        void timeline_relationCategory_computesAddModDelDiffs() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);
            var source = makeRequirementWithId("REQ-SRC", UUID.randomUUID());
            var target = makeRequirementWithId("REQ-TGT", UUID.randomUUID());
            var relId = UUID.randomUUID();
            var relV1 = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
            setField(relV1, "id", relId);
            var relV2 = new RequirementRelation(source, target, RelationType.REFINES);
            setField(relV2, "id", relId);
            List<Object[]> rows = List.of(
                    new Object[] {relV1, rev(1, "alice"), RevisionType.ADD},
                    new Object[] {relV2, rev(2, "bob"), RevisionType.MOD},
                    new Object[] {relV2, rev(3, "carol"), RevisionType.DEL});

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                stubEnversQuery(RequirementRelation.class, rows);

                var timeline =
                        service.getRequirementTimeline(REQ_ID, ChangeCategory.RELATION, null, null, null, 100, 0);

                assertThat(timeline).hasSize(3);
                assertThat(byType(timeline, "ADD").changes().get("relationType").newValue())
                        .isEqualTo("DEPENDS_ON");
                assertThat(byType(timeline, "MOD").changes().get("relationType").oldValue())
                        .isEqualTo("DEPENDS_ON");
                assertThat(byType(timeline, "MOD").changes().get("relationType").newValue())
                        .isEqualTo("REFINES");
                assertThat(byType(timeline, "DEL").changes().get("relationType").oldValue())
                        .isEqualTo("REFINES");
                assertThat(byType(timeline, "DEL").changes().get("relationType").newValue())
                        .isNull();
            }
        }

        @Test
        void timeline_traceabilityLinkCategory_computesAddModDelDiffs() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);
            var owner = makeRequirementWithId("REQ-OWN", UUID.randomUUID());
            var linkId = UUID.randomUUID();
            var linkV1 = new TraceabilityLink(owner, ArtifactType.CODE_FILE, "src/X.java", LinkType.IMPLEMENTS);
            setField(linkV1, "id", linkId);
            var linkV2 = new TraceabilityLink(owner, ArtifactType.CODE_FILE, "src/X.java", LinkType.TESTS);
            setField(linkV2, "id", linkId);
            List<Object[]> rows = List.of(
                    new Object[] {linkV1, rev(1, "alice"), RevisionType.ADD},
                    new Object[] {linkV2, rev(2, "bob"), RevisionType.MOD},
                    new Object[] {linkV2, rev(3, "carol"), RevisionType.DEL});

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                stubEnversQuery(TraceabilityLink.class, rows);

                var timeline = service.getRequirementTimeline(
                        REQ_ID, ChangeCategory.TRACEABILITY_LINK, null, null, null, 100, 0);

                assertThat(timeline).hasSize(3);
                assertThat(byType(timeline, "ADD").changes().get("linkType").newValue())
                        .isEqualTo("IMPLEMENTS");
                assertThat(byType(timeline, "MOD").changes().get("linkType").oldValue())
                        .isEqualTo("IMPLEMENTS");
                assertThat(byType(timeline, "MOD").changes().get("linkType").newValue())
                        .isEqualTo("TESTS");
                assertThat(byType(timeline, "DEL").changes().get("linkType").oldValue())
                        .isEqualTo("TESTS");
                assertThat(byType(timeline, "DEL").changes().get("linkType").newValue())
                        .isNull();
            }
        }
    }
}
