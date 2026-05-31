package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAppetiteProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAppetiteProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.AppetiteToleranceKind;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskAppetiteProfileServiceTest {

    @Mock
    private RiskAppetiteProfileRepository repository;

    @Mock
    private ProjectService projectService;

    private RiskAppetiteProfileService service;

    private Project project;
    private UUID projectId;
    private UUID profileId;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new RiskAppetiteProfileService(repository, projectService, validator);

        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        profileId = UUID.randomUUID();
    }

    private RiskAppetiteProfile makeProfile() {
        var profile = new RiskAppetiteProfile(project, "BOARD_2026", "Board Appetite 2026", "1");
        setField(profile, "id", profileId);
        return profile;
    }

    private RiskAppetiteTolerance cyberMonetaryTolerance() {
        return new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.MONETARY_RANGE,
                null,
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                "USD",
                null,
                null,
                null,
                null);
    }

    @Test
    void createBuildsProfileAndPersists() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "BOARD_2026", "1"))
                .thenReturn(false);
        when(repository.save(any(RiskAppetiteProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(new CreateRiskAppetiteProfileCommand(
                projectId,
                "BOARD_2026",
                "Board Appetite 2026",
                "1",
                "Moderate tolerance for operational risk.",
                "CISO",
                false,
                List.of(cyberMonetaryTolerance())));

        assertThat(result.getProfileKey()).isEqualTo("BOARD_2026");
        assertThat(result.getOwner()).isEqualTo("CISO");
        assertThat(result.getTolerances()).hasSize(1);
        assertThat(result.getTolerances().get(0).category()).isEqualTo("CYBER");
    }

    @Test
    void createRejectsDuplicateKeyAndVersion() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "BOARD_2026", "1"))
                .thenReturn(true);
        var cmd = new CreateRiskAppetiteProfileCommand(projectId, "BOARD_2026", "Name", "1", null, null, null, null);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("BOARD_2026");
    }

    @Test
    void createRejectsDuplicateToleranceBand() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "BOARD_2026", "1"))
                .thenReturn(false);
        var duplicate = List.of(cyberMonetaryTolerance(), cyberMonetaryTolerance());
        var cmd =
                new CreateRiskAppetiteProfileCommand(projectId, "BOARD_2026", "Name", "1", null, null, null, duplicate);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void getByIdThrowsNotFoundWhenAbsent() {
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(projectId, profileId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(profileId.toString());
    }

    @Test
    void getByIdReturnsProfileWhenPresent() {
        var profile = makeProfile();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        var result = service.getById(projectId, profileId);

        assertThat(result.getProfileKey()).isEqualTo("BOARD_2026");
    }

    @Test
    void updateMutatesFieldsAndPersists() {
        var profile = makeProfile();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        var result = service.update(
                projectId,
                profileId,
                new UpdateRiskAppetiteProfileCommand("Updated Name", "2", "New statement", "New Owner", false, null));

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getVersion()).isEqualTo("2");
        assertThat(result.getOwner()).isEqualTo("New Owner");
    }

    @Test
    void updateThrowsNotFoundWhenProfileAbsent() {
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.empty());
        var cmd = new UpdateRiskAppetiteProfileCommand(null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(projectId, profileId, cmd)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesProfile() {
        var profile = makeProfile();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        service.delete(projectId, profileId);

        org.mockito.Mockito.verify(repository).delete(profile);
    }

    @Test
    void deleteThrowsNotFoundWhenAbsent() {
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(projectId, profileId)).isInstanceOf(NotFoundException.class);
    }
}
