package com.keplerops.groundcontrol.unit.domain.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.decisions.model.DecisionAnalysisRecord;
import com.keplerops.groundcontrol.domain.decisions.repository.DecisionAnalysisRecordRepository;
import com.keplerops.groundcontrol.domain.decisions.service.CreateDecisionAnalysisRecordCommand;
import com.keplerops.groundcontrol.domain.decisions.service.DecisionAnalysisRecordService;
import com.keplerops.groundcontrol.domain.decisions.service.UpdateDecisionAnalysisRecordCommand;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Service-layer security tests for DecisionAnalysisRecord mutations.
 *
 * <p>Focus: the Envers-audited record must always carry the authenticated
 * principal in the inputs-map attribution slot so a malicious caller cannot
 * record decision inputs that the audit trail blames on someone else.
 */
class DecisionAnalysisRecordServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final String ATTRIBUTED_TO_KEY = "_attributedTo";

    private DecisionAnalysisRecordRepository repository;
    private ProjectService projectService;
    private DecisionAnalysisRecordService service;
    private Project project;

    @BeforeEach
    void setup() {
        repository = mock(DecisionAnalysisRecordRepository.class);
        projectService = mock(ProjectService.class);
        service = new DecisionAnalysisRecordService(repository, projectService);
        project = new Project("ground-control", "Ground Control");
        TestUtil.setField(project, "id", PROJECT_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(any(), any())).thenReturn(false);
        when(repository.save(any(DecisionAnalysisRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        ActorHolder.set("alice");
    }

    @AfterEach
    void tearDown() {
        ActorHolder.clear();
    }

    @Test
    void createOverwritesClientSuppliedAttributedToInInputsMap() {
        var clientForged = new LinkedHashMap<String, Object>();
        clientForged.put("buy.cost", 100_000);
        clientForged.put(ATTRIBUTED_TO_KEY, "bob");

        var command = new CreateDecisionAnalysisRecordCommand(
                PROJECT_ID, "DR-1", "Buy vs build", "monte_carlo", null, clientForged, null, null, null, null, null);

        var saved = service.create(command);

        assertThat(saved.getCreatedBy()).isEqualTo("alice");
        assertThat(saved.getInputs()).containsEntry(ATTRIBUTED_TO_KEY, "alice");
        // Original input data preserved.
        assertThat(saved.getInputs()).containsEntry("buy.cost", 100_000);
    }

    @Test
    void createStampsAttributedToWhenAbsent() {
        var inputs = Map.<String, Object>of("buy.cost", 100_000);

        var command = new CreateDecisionAnalysisRecordCommand(
                PROJECT_ID, "DR-1", "Buy vs build", "monte_carlo", null, inputs, null, null, null, null, null);

        var saved = service.create(command);

        assertThat(saved.getInputs()).containsEntry(ATTRIBUTED_TO_KEY, "alice");
    }

    @Test
    void updateOverwritesClientSuppliedAttributedToOnInputsReplacement() {
        var existing = new DecisionAnalysisRecord(project, "DR-1", "Buy vs build", "monte_carlo");
        var recordId = UUID.randomUUID();
        TestUtil.setField(existing, "id", recordId);
        when(repository.findByIdAndProjectId(recordId, PROJECT_ID)).thenReturn(Optional.of(existing));

        var clientForged = new LinkedHashMap<String, Object>();
        clientForged.put("build.cost", 75_000);
        clientForged.put(ATTRIBUTED_TO_KEY, "mallory");

        var command =
                new UpdateDecisionAnalysisRecordCommand(null, null, null, clientForged, null, null, null, null, null);

        var saved = service.update(PROJECT_ID, recordId, command);

        assertThat(saved.getInputs()).containsEntry(ATTRIBUTED_TO_KEY, "alice");
        assertThat(saved.getInputs()).containsEntry("build.cost", 75_000);
    }

    @Test
    void updateLeavesInputsUntouchedWhenAbsent() {
        var existing = new DecisionAnalysisRecord(project, "DR-1", "Buy vs build", "monte_carlo");
        existing.setInputs(Map.of("buy.cost", 100_000, ATTRIBUTED_TO_KEY, "alice"));
        var recordId = UUID.randomUUID();
        TestUtil.setField(existing, "id", recordId);
        when(repository.findByIdAndProjectId(recordId, PROJECT_ID)).thenReturn(Optional.of(existing));

        var command =
                new UpdateDecisionAnalysisRecordCommand("Renamed", null, null, null, null, null, null, null, null);

        var saved = service.update(PROJECT_ID, recordId, command);

        assertThat(saved.getTitle()).isEqualTo("Renamed");
        assertThat(saved.getInputs()).containsEntry("buy.cost", 100_000);
    }

    @Test
    void createWithNullInputsStampsAttributionOnly() {
        var command = new CreateDecisionAnalysisRecordCommand(
                PROJECT_ID, "DR-2", "Null inputs test", "monte_carlo", null, null, null, null, null, null, null);

        var saved = service.create(command);

        // stampAttribution with null inputs produces a map containing only the attribution key
        assertThat(saved.getInputs()).containsOnlyKeys(ATTRIBUTED_TO_KEY);
        assertThat(saved.getInputs()).containsEntry(ATTRIBUTED_TO_KEY, "alice");
    }
}
