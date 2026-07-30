package com.keplerops.groundcontrol.domain.requirements.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from AuditServiceDiffTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class AuditServiceDiffTest {
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
    class GetRequirementDiff {

        @Test
        void throwsNotFoundWhenRequirementDoesNotExist() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.getRequirementDiff(REQ_ID, 1, 5)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsValidationErrorWhenFromRevisionNotLessThanToRevision() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.getRequirementDiff(REQ_ID, 5, 5))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("fromRevision must be less than toRevision");
        }

        @Test
        void throwsValidationErrorWhenRequirementNotAtFromRevision() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                when(auditReader.find(Requirement.class, REQ_ID, 1)).thenReturn(null);

                assertThatThrownBy(() -> service.getRequirementDiff(REQ_ID, 1, 5))
                        .isInstanceOf(DomainValidationException.class)
                        .hasMessageContaining("does not exist at revision 1");
            }
        }

        @Test
        void throwsValidationErrorWhenRequirementNotAtToRevision() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);
            var fromReq = makeRequirement("REQ-001", "Title");

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                when(auditReader.find(Requirement.class, REQ_ID, 1)).thenReturn(fromReq);
                when(auditReader.find(Requirement.class, REQ_ID, 5)).thenReturn(null);

                assertThatThrownBy(() -> service.getRequirementDiff(REQ_ID, 1, 5))
                        .isInstanceOf(DomainValidationException.class)
                        .hasMessageContaining("does not exist at revision 5");
            }
        }

        @Test
        void returnsStructuredDiffBetweenTwoRevisions() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);
            var fromReq = makeRequirement("REQ-001", "Old Title");
            var toReq = makeRequirement("REQ-001", "New Title");

            var spyService = spy(service);

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                when(auditReader.find(Requirement.class, REQ_ID, 1)).thenReturn(fromReq);
                when(auditReader.find(Requirement.class, REQ_ID, 5)).thenReturn(toReq);

                doReturn(Map.<UUID, Map<String, Object>>of())
                        .when(spyService)
                        .getRelationSnapshotsAtRevision(REQ_ID, 1);
                doReturn(Map.<UUID, Map<String, Object>>of())
                        .when(spyService)
                        .getRelationSnapshotsAtRevision(REQ_ID, 5);
                doReturn(Map.<UUID, Map<String, Object>>of())
                        .when(spyService)
                        .getTraceabilityLinkSnapshotsAtRevision(REQ_ID, 1);
                doReturn(Map.<UUID, Map<String, Object>>of())
                        .when(spyService)
                        .getTraceabilityLinkSnapshotsAtRevision(REQ_ID, 5);

                var diff = spyService.getRequirementDiff(REQ_ID, 1, 5);

                assertThat(diff.requirementId()).isEqualTo(REQ_ID);
                assertThat(diff.fromRevision()).isEqualTo(1);
                assertThat(diff.toRevision()).isEqualTo(5);
                assertThat(diff.fieldChanges()).containsKey("title");
                assertThat(diff.fieldChanges().get("title").oldValue()).isEqualTo("Old Title");
                assertThat(diff.fieldChanges().get("title").newValue()).isEqualTo("New Title");
                assertThat(diff.relationChanges()).isEmpty();
                assertThat(diff.traceabilityLinkChanges()).isEmpty();
            }
        }

        @Test
        void includesRelationAndLinkChangesInDiff() {
            when(requirementRepository.existsById(REQ_ID)).thenReturn(true);
            var fromReq = makeRequirement("REQ-001", "Title");
            var toReq = makeRequirement("REQ-001", "Title");

            var relId = UUID.randomUUID();
            var linkId = UUID.randomUUID();
            var spyService = spy(service);

            try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
                factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
                when(auditReader.find(Requirement.class, REQ_ID, 1)).thenReturn(fromReq);
                when(auditReader.find(Requirement.class, REQ_ID, 5)).thenReturn(toReq);

                doReturn(Map.<UUID, Map<String, Object>>of())
                        .when(spyService)
                        .getRelationSnapshotsAtRevision(REQ_ID, 1);
                doReturn(Map.of(relId, Map.<String, Object>of("relationType", "DEPENDS_ON")))
                        .when(spyService)
                        .getRelationSnapshotsAtRevision(REQ_ID, 5);

                doReturn(Map.of(linkId, Map.<String, Object>of("artifactType", "CODE_FILE")))
                        .when(spyService)
                        .getTraceabilityLinkSnapshotsAtRevision(REQ_ID, 1);
                doReturn(Map.<UUID, Map<String, Object>>of())
                        .when(spyService)
                        .getTraceabilityLinkSnapshotsAtRevision(REQ_ID, 5);

                var diff = spyService.getRequirementDiff(REQ_ID, 1, 5);

                assertThat(diff.fieldChanges()).isEmpty();
                assertThat(diff.relationChanges()).hasSize(1);
                assertThat(diff.relationChanges().get(0).changeType()).isEqualTo(ChangeType.ADDED);
                assertThat(diff.relationChanges().get(0).relationId()).isEqualTo(relId);
                assertThat(diff.traceabilityLinkChanges()).hasSize(1);
                assertThat(diff.traceabilityLinkChanges().get(0).changeType()).isEqualTo(ChangeType.REMOVED);
                assertThat(diff.traceabilityLinkChanges().get(0).linkId()).isEqualTo(linkId);
            }
        }
    }

    @Nested
    class GetRelationHistory {

        @Test
        void throwsNotFoundWhenRelationDoesNotExist() {
            var relationId = UUID.randomUUID();
            when(relationRepository.findById(relationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getRelationHistory(REQ_ID, relationId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(relationId.toString());
        }

        @Test
        void throwsNotFoundWhenRequirementIsNeitherSourceNorTarget() {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = makeRequirementWithId("REQ-SRC", sourceId);
            var target = makeRequirementWithId("REQ-TGT", targetId);
            var relation = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
            var relationId = UUID.randomUUID();
            setField(relation, "id", relationId);
            when(relationRepository.findById(relationId)).thenReturn(Optional.of(relation));

            var unrelatedRequirementId = UUID.randomUUID();
            assertThatThrownBy(() -> service.getRelationHistory(unrelatedRequirementId, relationId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(relationId.toString());
        }
    }

    @Nested
    class GetTraceabilityLinkHistory {

        @Test
        void throwsNotFoundWhenLinkDoesNotExist() {
            var linkId = UUID.randomUUID();
            when(traceabilityLinkRepository.findById(linkId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTraceabilityLinkHistory(REQ_ID, linkId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(linkId.toString());
        }

        @Test
        void throwsNotFoundWhenRequirementDoesNotOwnLink() {
            var ownerId = UUID.randomUUID();
            var owner = makeRequirementWithId("REQ-OWNER", ownerId);
            var link = new TraceabilityLink(owner, ArtifactType.GITHUB_ISSUE, "123", LinkType.IMPLEMENTS);
            var linkId = UUID.randomUUID();
            setField(link, "id", linkId);
            when(traceabilityLinkRepository.findById(linkId)).thenReturn(Optional.of(link));

            var unrelatedRequirementId = UUID.randomUUID();
            assertThatThrownBy(() -> service.getTraceabilityLinkHistory(unrelatedRequirementId, linkId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(linkId.toString());
        }
    }

    @Nested
    class SnapshotMapperDiffMethods {

        @Test
        void computeAdditionDiff_producesNullToValueChanges() {
            var snapshot = Map.<String, Object>of("title", "First Title", "status", "DRAFT");

            var diff = SnapshotMapper.computeAdditionDiff(snapshot);

            assertThat(diff).containsKey("title");
            assertThat(diff.get("title").oldValue()).isNull();
            assertThat(diff.get("title").newValue()).isEqualTo("First Title");
            assertThat(diff).containsKey("status");
            assertThat(diff.get("status").oldValue()).isNull();
            assertThat(diff.get("status").newValue()).isEqualTo("DRAFT");
        }

        @Test
        void computeRemovalDiff_producesValueToNullChanges() {
            var snapshot = Map.<String, Object>of("title", "Deleted Title", "status", "ACTIVE");

            var diff = SnapshotMapper.computeRemovalDiff(snapshot);

            assertThat(diff).containsKey("title");
            assertThat(diff.get("title").oldValue()).isEqualTo("Deleted Title");
            assertThat(diff.get("title").newValue()).isNull();
            assertThat(diff).containsKey("status");
            assertThat(diff.get("status").oldValue()).isEqualTo("ACTIVE");
            assertThat(diff.get("status").newValue()).isNull();
        }

        @Test
        void computeRemovalDiff_skipsNullValues() {
            var snapshot = new java.util.LinkedHashMap<String, Object>();
            snapshot.put("title", "Value");
            snapshot.put("statement", null);

            var diff = SnapshotMapper.computeRemovalDiff(snapshot);

            assertThat(diff).containsKey("title").doesNotContainKey("statement");
        }

        @Test
        void computeAdditionDiff_emptySnapshot_producesEmptyDiff() {
            var diff = SnapshotMapper.computeAdditionDiff(Map.of());
            assertThat(diff).isEmpty();
        }

        @Test
        void computeRemovalDiff_emptySnapshot_producesEmptyDiff() {
            var diff = SnapshotMapper.computeRemovalDiff(Map.of());
            assertThat(diff).isEmpty();
        }
    }

    @Nested
    class ReplayToAliveSnapshots {

        @Test
        void addsEntitiesAndRemovesDeleted() {
            var id1 = UUID.randomUUID();
            var id2 = UUID.randomUUID();
            var snap1 = Map.<String, Object>of("field", "value1");
            var snap2 = Map.<String, Object>of("field", "value2");

            List<Object[]> results = List.of(
                    new Object[] {null, null, RevisionType.ADD, id1, snap1},
                    new Object[] {null, null, RevisionType.ADD, id2, snap2},
                    new Object[] {null, null, RevisionType.DEL, id1, snap1});

            var alive = service.replayToAliveSnapshots(
                    results, row -> Map.entry((UUID) row[3], (Map<String, Object>) row[4]), row ->
                            (RevisionType) row[2]);

            assertThat(alive).hasSize(1).containsKey(id2).doesNotContainKey(id1);
        }

        @Test
        void emptyResultsProduceEmptyMap() {
            var alive = service.replayToAliveSnapshots(
                    List.of(), row -> Map.entry(UUID.randomUUID(), Map.of()), row -> RevisionType.ADD);

            assertThat(alive).isEmpty();
        }

        @Test
        void modUpdatesSnapshot() {
            var id = UUID.randomUUID();
            var snap1 = Map.<String, Object>of("field", "v1");
            var snap2 = Map.<String, Object>of("field", "v2");

            List<Object[]> results = List.of(
                    new Object[] {null, null, RevisionType.ADD, id, snap1},
                    new Object[] {null, null, RevisionType.MOD, id, snap2});

            var alive = service.replayToAliveSnapshots(
                    results, row -> Map.entry((UUID) row[3], (Map<String, Object>) row[4]), row ->
                            (RevisionType) row[2]);

            assertThat(alive).hasSize(1);
            assertThat(alive.get(id)).containsEntry("field", "v2");
        }
    }
}
