package com.keplerops.groundcontrol.unit.domain.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.repository.BacklogItemRepository;
import com.keplerops.groundcontrol.domain.backlog.service.BacklogItemService;
import com.keplerops.groundcontrol.domain.backlog.service.CreateBacklogItemCommand;
import com.keplerops.groundcontrol.domain.backlog.service.UpdateBacklogItemCommand;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Service-layer security tests for BacklogItem mutations.
 *
 * <p>Focus: the Envers-audited CoD components must always carry the
 * authenticated principal in {@code attributedTo} so a malicious caller cannot
 * record an estimate that the audit trail blames on someone else.
 */
class BacklogItemServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");

    private BacklogItemRepository repository;
    private ProjectService projectService;
    private BacklogItemService service;
    private Project project;

    @BeforeEach
    void setup() {
        repository = mock(BacklogItemRepository.class);
        projectService = mock(ProjectService.class);
        service = new BacklogItemService(repository, projectService);
        project = new Project("ground-control", "Ground Control");
        TestUtil.setField(project, "id", PROJECT_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(any(), any())).thenReturn(false);
        when(repository.save(any(BacklogItem.class))).thenAnswer(inv -> inv.getArgument(0));
        ActorHolder.set("alice");
    }

    @AfterEach
    void tearDown() {
        ActorHolder.clear();
    }

    @Test
    void createOverwritesClientSuppliedAttributedToWithAuthenticatedActor() {
        var clientForged = CostOfDelayComponent.triangular(1, 2, 3, "bob");
        var jd = CostOfDelayComponent.point(1, "carol");

        var command = new CreateBacklogItemCommand(
                PROJECT_ID, "BI-1", "Feature", null, clientForged, clientForged, clientForged, jd);

        var saved = service.create(command);

        assertThat(saved.getCreatedBy()).isEqualTo("alice");
        assertThat(saved.getUserBusinessValue().attributedTo()).isEqualTo("alice");
        assertThat(saved.getTimeCriticality().attributedTo()).isEqualTo("alice");
        assertThat(saved.getRiskReductionOpportunityEnablement().attributedTo()).isEqualTo("alice");
        assertThat(saved.getJobDuration().attributedTo()).isEqualTo("alice");
    }

    @Test
    void updateOverwritesAttributedToOnReplacedComponents() {
        var existing = new BacklogItem(project, "BI-1", "Feature");
        existing.setUserBusinessValue(CostOfDelayComponent.point(2, "alice"));
        existing.setTimeCriticality(CostOfDelayComponent.point(2, "alice"));
        existing.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        existing.setJobDuration(CostOfDelayComponent.point(1, "alice"));
        var itemId = UUID.randomUUID();
        TestUtil.setField(existing, "id", itemId);
        when(repository.findByIdAndProjectId(itemId, PROJECT_ID)).thenReturn(Optional.of(existing));

        var clientForged = CostOfDelayComponent.triangular(1, 5, 10, "mallory");
        var command = new UpdateBacklogItemCommand(null, null, clientForged, null, null, null);

        var saved = service.update(PROJECT_ID, itemId, command);

        assertThat(saved.getUserBusinessValue().attributedTo()).isEqualTo("alice");
        // Untouched components retained their original attribution.
        assertThat(saved.getTimeCriticality().attributedTo()).isEqualTo("alice");
    }

    @Test
    void nullComponentsArePreservedOnPartialUpdate() {
        var existing = new BacklogItem(project, "BI-1", "Feature");
        existing.setUserBusinessValue(CostOfDelayComponent.point(2, "alice"));
        var itemId = UUID.randomUUID();
        TestUtil.setField(existing, "id", itemId);
        when(repository.findByIdAndProjectId(itemId, PROJECT_ID)).thenReturn(Optional.of(existing));

        var command = new UpdateBacklogItemCommand("New title", null, null, null, null, null);

        var saved = service.update(PROJECT_ID, itemId, command);

        assertThat(saved.getTitle()).isEqualTo("New title");
        // No NPE; null CoD components stay null through stamp().
        assertThat(saved.getJobDuration()).isNull();
    }
}
