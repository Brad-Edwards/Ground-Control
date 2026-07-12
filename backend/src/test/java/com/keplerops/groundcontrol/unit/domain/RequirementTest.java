package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.*;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.state.*;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S2187") // Tests are in @Nested inner classes
class RequirementTest {

    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return project;
    }

    private static Requirement createRequirement(String uid) {
        return new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
    }

    private static void setId(Requirement req, UUID id) {
        TestUtil.setField(req, "id", id);
    }

    @Nested
    class Defaults {

        @Test
        void newRequirementHasDraftStatus() {
            var req = createRequirement("REQ-001");
            assertThat(req.getStatus()).isEqualTo(Status.DRAFT);
        }

        @Test
        void newRequirementHasFunctionalType() {
            var req = createRequirement("REQ-001");
            assertThat(req.getRequirementType()).isEqualTo(RequirementType.FUNCTIONAL);
        }

        @Test
        void newRequirementHasMustPriority() {
            var req = createRequirement("REQ-001");
            assertThat(req.getPriority()).isEqualTo(Priority.MUST);
        }

        @Test
        void newRequirementHasNullArchivedAt() {
            var req = createRequirement("REQ-001");
            assertThat(req.getArchivedAt()).isNull();
        }
    }

    @Nested
    class StatusTransitions {

        @Test
        void draftToActiveSucceeds() {
            var req = createRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            assertThat(req.getStatus()).isEqualTo(Status.ACTIVE);
        }

        @Test
        void activeToDeprecatedSucceeds() {
            var req = createRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            req.transitionStatus(Status.DEPRECATED);
            assertThat(req.getStatus()).isEqualTo(Status.DEPRECATED);
        }

        @Test
        void activeToArchivedSucceeds() {
            var req = createRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            req.transitionStatus(Status.ARCHIVED);
            assertThat(req.getStatus()).isEqualTo(Status.ARCHIVED);
        }

        @Test
        void deprecatedToArchivedSucceeds() {
            var req = createRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            req.transitionStatus(Status.DEPRECATED);
            req.transitionStatus(Status.ARCHIVED);
            assertThat(req.getStatus()).isEqualTo(Status.ARCHIVED);
        }

        @Test
        void draftToArchivedFails() {
            var req = createRequirement("REQ-001");
            assertThatThrownBy(() -> req.transitionStatus(Status.ARCHIVED))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("DRAFT")
                    .hasMessageContaining("ARCHIVED");
        }

        @Test
        void archivedToAnyFails() {
            var req = createRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            req.transitionStatus(Status.ARCHIVED);
            for (Status target : Status.values()) {
                if (target == Status.ARCHIVED) continue;
                assertThatThrownBy(() -> req.transitionStatus(target)).isInstanceOf(DomainValidationException.class);
            }
        }

        @Test
        void draftToDeprecatedWithdrawsWithoutPassingThroughActive() {
            var req = createRequirement("REQ-001");

            req.transitionStatus(Status.DEPRECATED);

            assertThat(req.getStatus()).isEqualTo(Status.DEPRECATED);
        }

        @Test
        void invalidTransitionContainsErrorCodeAndDetail() {
            var req = createRequirement("REQ-001");
            assertThatThrownBy(() -> req.transitionStatus(Status.ARCHIVED))
                    .isInstanceOf(DomainValidationException.class)
                    .satisfies(ex -> {
                        var dve = (DomainValidationException) ex;
                        assertThat(dve.getErrorCode()).isEqualTo("invalid_status_transition");
                        assertThat(dve.getDetail()).containsEntry("current_status", "DRAFT");
                        assertThat(dve.getDetail()).containsEntry("target_status", "ARCHIVED");
                    });
        }
    }

    @Nested
    class Archive {

        @Test
        void archiveFromActiveSetsStatusAndTimestamp() {
            var req = createRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            req.archive();
            assertThat(req.getStatus()).isEqualTo(Status.ARCHIVED);
            assertThat(req.getArchivedAt()).isNotNull();
        }

        @Test
        void archiveIsIdempotent() {
            var req = createRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            req.archive();
            var firstArchivedAt = req.getArchivedAt();
            req.archive(); // should not throw
            assertThat(req.getArchivedAt()).isEqualTo(firstArchivedAt);
        }

        @Test
        void archiveFromDraftFails() {
            var req = createRequirement("REQ-001");
            assertThatThrownBy(req::archive).isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Accessors {

        @Test
        void settersAndGettersCoverAllFields() {
            var req = createRequirement("REQ-001");
            req.setStatement("new statement");
            assertThat(req.getStatement()).isEqualTo("new statement");
            req.setRationale("some rationale");
            assertThat(req.getRationale()).isEqualTo("some rationale");
            req.setWave(3);
            assertThat(req.getWave()).isEqualTo(3);
            assertThat(req.getCreatedAt()).isNull(); // not persisted
            assertThat(req.getUpdatedAt()).isNull();
            assertThat(req.toString()).isEqualTo("REQ-001");
        }
    }

    @Nested
    class Relations {

        @Test
        void selfLoopThrows() {
            var req = createRequirement("REQ-001");
            setId(req, UUID.randomUUID());
            assertThatThrownBy(() -> new RequirementRelation(req, req, RelationType.DEPENDS_ON))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("itself");
        }

        @Test
        void validRelationCreates() {
            var source = createRequirement("REQ-001");
            var target = createRequirement("REQ-002");
            setId(source, UUID.randomUUID());
            setId(target, UUID.randomUUID());
            var rel = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
            assertThat(rel.getSource()).isEqualTo(source);
            assertThat(rel.getTarget()).isEqualTo(target);
            assertThat(rel.getRelationType()).isEqualTo(RelationType.DEPENDS_ON);
        }

        @Test
        void relationAccessorsCoverAllFields() {
            var source = createRequirement("REQ-001");
            var target = createRequirement("REQ-002");
            setId(source, UUID.randomUUID());
            setId(target, UUID.randomUUID());
            var rel = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
            assertThat(rel.getId()).isNull(); // not persisted
            assertThat(rel.getCreatedAt()).isNull();
            assertThat(rel.getDescription()).isEmpty();
            rel.setDescription("some desc");
            assertThat(rel.getDescription()).isEqualTo("some desc");
            assertThat(rel.toString()).contains("REQ-001").contains("REQ-002").contains("DEPENDS_ON");
        }
    }
}
