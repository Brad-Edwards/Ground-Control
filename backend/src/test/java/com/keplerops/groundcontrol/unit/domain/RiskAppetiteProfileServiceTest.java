package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.repository.RiskAppetiteProfileRepository;
import com.keplerops.groundcontrol.domain.riskappetite.service.CreateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskappetite.service.RiskAppetiteProfileService;
import com.keplerops.groundcontrol.domain.riskappetite.service.UpdateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskAppetiteProfileServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-31T00:00:00Z");

    @Mock
    private RiskAppetiteProfileRepository repository;

    @Mock
    private ProjectService projectService;

    private RiskAppetiteProfileService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new RiskAppetiteProfileService(repository, projectService);
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
    }

    private CreateRiskAppetiteProfileCommand createCommand(
            List<ToleranceThreshold> thresholds, RiskAppetiteProfileStatus status, Instant from, Instant to) {
        return new CreateRiskAppetiteProfileCommand(
                PROJECT_ID,
                "BOARD_APPETITE",
                "Board Risk Appetite",
                "1.0",
                MethodologyFamily.FAIR,
                "statement",
                thresholds,
                status,
                from,
                to);
    }

    private ToleranceThreshold quantitative(double max) {
        return new ToleranceThreshold(
                "data-breach", "annualized_loss_expectancy.likely", max, "USD", "USD", null, null, "ALE");
    }

    @Test
    void createPersistsValidProfile() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(PROJECT_ID, "BOARD_APPETITE", "1.0"))
                .thenReturn(false);
        when(repository.save(any())).then(returnsFirstArg());

        var saved = service.create(
                createCommand(List.of(quantitative(500000.0)), RiskAppetiteProfileStatus.DRAFT, FROM, TO));

        assertThat(saved.getAppetiteKey()).isEqualTo("BOARD_APPETITE");
        assertThat(saved.getToleranceThresholds()).hasSize(1);
        verify(repository).save(any(RiskAppetiteProfile.class));
    }

    @Test
    void createRejectsDuplicateKeyVersion() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(PROJECT_ID, "BOARD_APPETITE", "1.0"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(createCommand(List.of(), RiskAppetiteProfileStatus.DRAFT, FROM, TO)))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsThresholdWithBothQuantitativeAndOrdinal() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        var bad = new ToleranceThreshold(null, "risk_level", 5.0, null, null, "HIGH", List.of("LOW", "HIGH"), null);

        assertThatThrownBy(() -> service.create(createCommand(List.of(bad), RiskAppetiteProfileStatus.DRAFT, FROM, TO)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void createRejectsThresholdWithNeitherCeiling() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        var bad = new ToleranceThreshold(null, "risk_value", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(createCommand(List.of(bad), RiskAppetiteProfileStatus.DRAFT, FROM, TO)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void createRejectsOrdinalThresholdMissingFromScale() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        var bad = new ToleranceThreshold(
                null, "risk_level", null, null, null, "CRITICAL", List.of("LOW", "MODERATE", "HIGH"), null);

        assertThatThrownBy(() -> service.create(createCommand(List.of(bad), RiskAppetiteProfileStatus.DRAFT, FROM, TO)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void createRejectsProbabilityAboveOne() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        var bad = new ToleranceThreshold(null, "exceedance_probability", 1.5, "probability", null, null, null, null);

        assertThatThrownBy(() -> service.create(createCommand(List.of(bad), RiskAppetiteProfileStatus.DRAFT, FROM, TO)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void createRejectsEffectiveToBeforeFrom() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(createCommand(List.of(), RiskAppetiteProfileStatus.DRAFT, TO, FROM)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void createActiveRejectsOverlappingWindow() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        var existing = new RiskAppetiteProfile(project, "BOARD_APPETITE", "old", "0.9", MethodologyFamily.FAIR, FROM);
        setField(existing, "id", UUID.randomUUID());
        existing.setStatus(RiskAppetiteProfileStatus.ACTIVE);
        when(repository.findByProjectIdAndAppetiteKeyAndStatus(
                        PROJECT_ID, "BOARD_APPETITE", RiskAppetiteProfileStatus.ACTIVE))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(createCommand(List.of(), RiskAppetiteProfileStatus.ACTIVE, FROM, TO)))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createActiveAllowsNonOverlappingWindow() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(repository.existsByProjectIdAndAppetiteKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        var earlier = new RiskAppetiteProfile(
                project, "BOARD_APPETITE", "old", "0.9", MethodologyFamily.FAIR, Instant.parse("2025-01-01T00:00:00Z"));
        setField(earlier, "id", UUID.randomUUID());
        earlier.setStatus(RiskAppetiteProfileStatus.ACTIVE);
        earlier.setEffectiveTo(Instant.parse("2025-12-31T00:00:00Z"));
        when(repository.findByProjectIdAndAppetiteKeyAndStatus(
                        PROJECT_ID, "BOARD_APPETITE", RiskAppetiteProfileStatus.ACTIVE))
                .thenReturn(List.of(earlier));
        when(repository.save(any())).then(returnsFirstArg());

        var saved = service.create(createCommand(List.of(), RiskAppetiteProfileStatus.ACTIVE, FROM, TO));

        assertThat(saved.getStatus()).isEqualTo(RiskAppetiteProfileStatus.ACTIVE);
    }

    @Test
    void updateAppliesProvidedFields() {
        var existing = new RiskAppetiteProfile(
                project, "BOARD_APPETITE", "Board Risk Appetite", "1.0", MethodologyFamily.FAIR, FROM);
        var id = UUID.randomUUID();
        setField(existing, "id", id);
        when(repository.findByIdAndProjectId(id, PROJECT_ID)).thenReturn(java.util.Optional.of(existing));
        when(repository.save(any())).then(returnsFirstArg());

        var updated = service.update(
                PROJECT_ID,
                id,
                new UpdateRiskAppetiteProfileCommand(
                        "Renamed", null, null, null, null, RiskAppetiteProfileStatus.RETIRED, null, null));

        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getStatus()).isEqualTo(RiskAppetiteProfileStatus.RETIRED);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(repository.findByIdAndProjectId(any(), any())).thenReturn(java.util.Optional.empty());
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> service.getById(PROJECT_ID, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteThrowsWhenMissing() {
        when(repository.findByIdAndProjectId(any(), any())).thenReturn(java.util.Optional.empty());
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> service.delete(PROJECT_ID, id)).isInstanceOf(NotFoundException.class);
    }
}
