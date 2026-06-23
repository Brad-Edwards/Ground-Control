package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CreateTraceabilityLinkCommand;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TraceabilityServiceTest {

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return project;
    }

    private TraceabilityService service;

    @BeforeEach
    void setUp() {
        service = new TraceabilityService(requirementRepository, traceabilityLinkRepository);
    }

    private static Requirement makeRequirement(String uid) {
        var req = new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", UUID.randomUUID());
        return req;
    }

    private static Requirement makeActiveRequirement(String uid) {
        var req = makeRequirement(uid);
        setField(req, "status", Status.ACTIVE);
        return req;
    }

    private static TraceabilityLink makeLink(Requirement req) {
        var link = new TraceabilityLink(req, ArtifactType.GITHUB_ISSUE, "GH-123", LinkType.IMPLEMENTS);
        setField(link, "id", UUID.randomUUID());
        return link;
    }

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
    }

    @Nested
    class CreateLink {

        @Test
        void createsLinkSuccessfully() {
            var reqId = UUID.randomUUID();
            var req = makeActiveRequirement("REQ-001");
            when(requirementRepository.findById(reqId)).thenReturn(Optional.of(req));
            when(traceabilityLinkRepository.save(any(TraceabilityLink.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateTraceabilityLinkCommand(
                    ArtifactType.GITHUB_ISSUE,
                    "GH-123",
                    "https://github.com/issue/123",
                    "Fix bug",
                    LinkType.IMPLEMENTS);

            var result = service.createLink(reqId, cmd);
            assertThat(result).isNotNull();
            assertThat(result.getArtifactType()).isEqualTo(ArtifactType.GITHUB_ISSUE);
            assertThat(result.getArtifactIdentifier()).isEqualTo("GH-123");
            assertThat(result.getLinkType()).isEqualTo(LinkType.IMPLEMENTS);
            assertThat(result.getArtifactUrl()).isEqualTo("https://github.com/issue/123");
            assertThat(result.getArtifactTitle()).isEqualTo("Fix bug");
        }

        @Test
        void throwsNotFoundForMissingRequirement() {
            var reqId = UUID.randomUUID();
            when(requirementRepository.findById(reqId)).thenReturn(Optional.empty());

            var cmd = new CreateTraceabilityLinkCommand(
                    ArtifactType.GITHUB_ISSUE, "GH-123", null, null, LinkType.IMPLEMENTS);

            assertThatThrownBy(() -> service.createLink(reqId, cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsImplementsLinkWhenRequirementIsDraft() {
            var reqId = UUID.randomUUID();
            var req = makeRequirement("REQ-DRAFT");
            when(requirementRepository.findById(reqId)).thenReturn(Optional.of(req));

            var cmd = new CreateTraceabilityLinkCommand(
                    ArtifactType.CODE_FILE, "src/Main.java", null, null, LinkType.IMPLEMENTS);

            assertThatThrownBy(() -> service.createLink(reqId, cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("ACTIVE");
        }

        @Test
        void rejectsImplementsLinkWhenRequirementIsDeprecated() {
            var reqId = UUID.randomUUID();
            var req = makeRequirement("REQ-DEP");
            setField(req, "status", Status.DEPRECATED);
            when(requirementRepository.findById(reqId)).thenReturn(Optional.of(req));

            var cmd = new CreateTraceabilityLinkCommand(
                    ArtifactType.CODE_FILE, "src/Main.java", null, null, LinkType.IMPLEMENTS);

            assertThatThrownBy(() -> service.createLink(reqId, cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("ACTIVE");
        }

        @Test
        void allowsNonImplementsLinkWhenRequirementIsDraft() {
            var reqId = UUID.randomUUID();
            var req = makeRequirement("REQ-DRAFT");
            when(requirementRepository.findById(reqId)).thenReturn(Optional.of(req));
            when(traceabilityLinkRepository.save(any(TraceabilityLink.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateTraceabilityLinkCommand(
                    ArtifactType.TEST, "src/test/MainTest.java", null, null, LinkType.TESTS);

            var result = service.createLink(reqId, cmd);
            assertThat(result.getLinkType()).isEqualTo(LinkType.TESTS);
        }
    }

    @Nested
    class GetLinks {

        @Test
        void returnsLinksSuccessfully() {
            var reqId = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            var link = makeLink(req);
            when(requirementRepository.existsById(reqId)).thenReturn(true);
            when(traceabilityLinkRepository.findByRequirementId(reqId)).thenReturn(List.of(link));

            var result = service.getLinksForRequirement(reqId);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getArtifactIdentifier()).isEqualTo("GH-123");
        }

        @Test
        void throwsNotFoundForMissingRequirement() {
            var reqId = UUID.randomUUID();
            when(requirementRepository.existsById(reqId)).thenReturn(false);

            assertThatThrownBy(() -> service.getLinksForRequirement(reqId)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class FindByArtifact {

        @Test
        void returnsMatchingLinks() {
            var projectId = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            var link = new TraceabilityLink(req, ArtifactType.CODE_FILE, "backend/src/Main.java", LinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            when(traceabilityLinkRepository.findByArtifactTypeAndArtifactIdentifierAndProjectIdWithRequirement(
                            ArtifactType.CODE_FILE, "backend/src/Main.java", projectId))
                    .thenReturn(List.of(link));

            var result = service.findByArtifact(ArtifactType.CODE_FILE, "backend/src/Main.java", projectId);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getArtifactIdentifier()).isEqualTo("backend/src/Main.java");
            assertThat(result.get(0).getLinkType()).isEqualTo(LinkType.IMPLEMENTS);
        }

        @Test
        void returnsEmptyWhenNoMatch() {
            var projectId = UUID.randomUUID();
            when(traceabilityLinkRepository.findByArtifactTypeAndArtifactIdentifierAndProjectIdWithRequirement(
                            ArtifactType.CODE_FILE, "nonexistent.java", projectId))
                    .thenReturn(List.of());

            var result = service.findByArtifact(ArtifactType.CODE_FILE, "nonexistent.java", projectId);
            assertThat(result).isEmpty();
        }

        @Test
        void rejectsNullProject() {
            // A null project would make the reverse lookup project-blind (#1052/#1197).
            assertThatThrownBy(() -> service.findByArtifact(ArtifactType.CODE_FILE, "backend/src/Main.java", null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class DeleteLink {

        @Test
        void deletesLinkSuccessfully() {
            var req = makeRequirement("REQ-001");
            var link = makeLink(req);
            var linkId = link.getId();
            when(traceabilityLinkRepository.findById(linkId)).thenReturn(Optional.of(link));

            service.deleteLink(req.getId(), linkId);
            verify(traceabilityLinkRepository).delete(link);
        }

        @Test
        void throwsNotFoundForMissingLink() {
            var linkId = UUID.randomUUID();
            when(traceabilityLinkRepository.findById(linkId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteLink(UUID.randomUUID(), linkId))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForMismatchedRequirement() {
            var req = makeRequirement("REQ-001");
            var link = makeLink(req);
            var linkId = link.getId();
            var wrongReqId = UUID.randomUUID();
            when(traceabilityLinkRepository.findById(linkId)).thenReturn(Optional.of(link));

            assertThatThrownBy(() -> service.deleteLink(wrongReqId, linkId)).isInstanceOf(NotFoundException.class);
        }
    }
}
