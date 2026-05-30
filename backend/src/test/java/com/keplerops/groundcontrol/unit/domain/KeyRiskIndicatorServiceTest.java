package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.KeyRiskIndicator;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.KeyRiskIndicatorRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateKeyRiskIndicatorCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.KeyRiskIndicatorService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RecordKriMeasurementCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateKeyRiskIndicatorCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.KriThresholdBand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class KeyRiskIndicatorServiceTest {

    @Mock
    private KeyRiskIndicatorRepository repository;

    @Mock
    private ProjectService projectService;

    @Mock
    private RiskRegisterRecordRepository riskRegisterRecordRepository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private KeyRiskIndicatorService service;

    private Project project;
    private UUID projectId;
    private UUID kriId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        kriId = UUID.randomUUID();
    }

    private KeyRiskIndicator makeKri() {
        var kri = new KeyRiskIndicator(project, "KRI-001", "Patch backlog");
        kri.setYellowThreshold(new BigDecimal("14"));
        kri.setRedThreshold(new BigDecimal("30"));
        setField(kri, "id", kriId);
        return kri;
    }

    @Test
    void createBuildsKriAndPersists() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "KRI-001")).thenReturn(false);
        when(repository.save(any(KeyRiskIndicator.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(new CreateKeyRiskIndicatorCommand(
                projectId,
                "KRI-001",
                "Patch backlog",
                "Days past due",
                "days",
                new BigDecimal("14"),
                new BigDecimal("30"),
                "HIGHER_IS_WORSE",
                "Owner",
                null,
                null));

        assertThat(result.getUid()).isEqualTo("KRI-001");
        assertThat(result.getName()).isEqualTo("Patch backlog");
        assertThat(result.getMetricUnit()).isEqualTo("days");
        assertThat(result.getYellowThreshold()).isEqualByComparingTo(new BigDecimal("14"));
        assertThat(result.getRedThreshold()).isEqualByComparingTo(new BigDecimal("30"));
    }

    @Test
    void createRejectsDuplicateUid() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "KRI-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateKeyRiskIndicatorCommand(
                        projectId, "KRI-001", "Name", null, null, null, null, null, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("KRI-001");
    }

    @Test
    void createRejectsInvalidDirection() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "KRI-001")).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateKeyRiskIndicatorCommand(
                        projectId, "KRI-001", "Name", null, null, null, null, "LOWER_IS_BETTER", null, null, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("direction");
    }

    @Test
    void getByIdThrowsNotFoundWhenAbsent() {
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(projectId, kriId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(kriId.toString());
    }

    @Test
    void getByIdReturnsKriWhenPresent() {
        var kri = makeKri();
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.of(kri));

        var result = service.getById(projectId, kriId);

        assertThat(result.getUid()).isEqualTo("KRI-001");
    }

    @Test
    void updateMutatesFieldsAndPersists() {
        var kri = makeKri();
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.of(kri));
        when(repository.save(kri)).thenReturn(kri);

        var result = service.update(
                projectId,
                kriId,
                new UpdateKeyRiskIndicatorCommand(
                        "New name", null, "percent", null, null, null, "New owner", null, null));

        assertThat(result.getName()).isEqualTo("New name");
        assertThat(result.getMetricUnit()).isEqualTo("percent");
        assertThat(result.getOwner()).isEqualTo("New owner");
    }

    @Test
    void updateThrowsNotFoundWhenKriAbsent() {
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                        projectId,
                        kriId,
                        new UpdateKeyRiskIndicatorCommand(null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void recordMeasurementThrowsWhenValueNull() {
        assertThatThrownBy(
                        () -> service.recordMeasurement(projectId, kriId, new RecordKriMeasurementCommand(null, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("null");
    }

    @Test
    void recordMeasurementUpdatesKriStateAndPersists() {
        var kri = makeKri();
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.of(kri));
        when(repository.save(kri)).thenReturn(kri);

        var measuredAt = Instant.parse("2026-04-04T12:00:00Z");
        var result = service.recordMeasurement(
                projectId, kriId, new RecordKriMeasurementCommand(new BigDecimal("45"), measuredAt));

        assertThat(result.getCurrentBand()).isEqualTo(KriThresholdBand.RED);
        assertThat(result.getCurrentValue()).isEqualByComparingTo(new BigDecimal("45"));
        assertThat(result.getLastMeasuredAt()).isEqualTo(measuredAt);
    }

    @Test
    void recordMeasurementPublishesBreachEventOnRedTransition() {
        var kri = makeKri();
        // currentBand starts null (not RED), so a RED reading should publish
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.of(kri));
        when(repository.save(kri)).thenReturn(kri);

        service.recordMeasurement(projectId, kriId, new RecordKriMeasurementCommand(new BigDecimal("45"), null));

        verify(eventPublisher, atLeastOnce()).publishEvent(any(Object.class));
    }

    @Test
    void recordMeasurementDoesNotPublishWhenAlreadyRed() {
        var kri = makeKri();
        // Pre-set band to RED so transition RED→RED does not re-publish
        setField(kri, "currentBand", KriThresholdBand.RED);
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.of(kri));
        when(repository.save(kri)).thenReturn(kri);

        service.recordMeasurement(projectId, kriId, new RecordKriMeasurementCommand(new BigDecimal("50"), null));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void deleteRemovesKri() {
        var kri = makeKri();
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.of(kri));

        service.delete(projectId, kriId);

        verify(repository).delete(kri);
    }

    @Test
    void deleteThrowsNotFoundWhenAbsent() {
        when(repository.findByIdAndProjectId(kriId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(projectId, kriId)).isInstanceOf(NotFoundException.class);
    }
}
