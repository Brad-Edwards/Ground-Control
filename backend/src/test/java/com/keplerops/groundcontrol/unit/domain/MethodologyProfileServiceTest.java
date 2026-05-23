package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateMethodologyProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.MethodologyProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateMethodologyProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MethodologyProfileServiceTest {

    @Mock
    private MethodologyProfileRepository repository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private MethodologyProfileService service;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Test
    void createPersistsConfiguredProfile() {
        var command = new CreateMethodologyProfileCommand(
                projectId,
                "FAIR_V3_0",
                "FAIR",
                "3.0",
                MethodologyFamily.FAIR,
                "Quantitative method",
                Map.of("type", "object"),
                Map.of("result", "object"),
                MethodologyProfileStatus.DEPRECATED,
                Map.of("RESIDUAL_TRANSFER", Map.of()));
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "FAIR_V3_0", "3.0"))
                .thenReturn(false);
        when(repository.save(any(MethodologyProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(command);

        assertThat(result.getProject()).isSameAs(project);
        assertThat(result.getProfileKey()).isEqualTo("FAIR_V3_0");
        assertThat(result.getDescription()).isEqualTo("Quantitative method");
        assertThat(result.getStatus()).isEqualTo(MethodologyProfileStatus.DEPRECATED);
        assertThat(result.getTreatmentStrategyVocabulary()).containsKey("RESIDUAL_TRANSFER");
    }

    @Test
    void createRejectsDuplicateProfileVersion() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(projectId, "FAIR_V3_0", "3.0"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateMethodologyProfileCommand(
                        projectId, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR, null, null, null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void listByProjectSeedsDefaultsBeforeReading() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndProfileKeyAndVersion(any(), any(), any()))
                .thenReturn(false);
        when(repository.findByProjectIdOrderByNameAscVersionDesc(projectId)).thenReturn(List.of());

        var result = service.listByProject(projectId);

        assertThat(result).isEmpty();
        var savedCaptor = ArgumentCaptor.forClass(MethodologyProfile.class);
        verify(repository, times(4)).save(savedCaptor.capture());
        assertThat(savedCaptor.getAllValues())
                .extracting(MethodologyProfile::getProfileKey)
                .containsExactlyInAnyOrder("LEGACY_QUALITATIVE_V1", "FAIR_V3_0", "NIST_SP800_30_R1", "ISO_27005_V2022");
        assertThat(savedCaptor.getAllValues())
                .allSatisfy(saved -> assertThat(saved.getProject()).isSameAs(project));
        verify(repository).findByProjectIdOrderByNameAscVersionDesc(projectId);
    }

    @Test
    void getByIdThrowsWhenProfileIsMissing() {
        var profileId = UUID.randomUUID();
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(projectId, profileId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateMutatesAllMutableFields() {
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);

        var updated = service.update(
                projectId,
                profileId,
                new UpdateMethodologyProfileCommand(
                        "Updated FAIR",
                        "3.1",
                        MethodologyFamily.CUSTOM,
                        "Updated description",
                        Map.of("factor", "schema"),
                        Map.of("output", "schema"),
                        MethodologyProfileStatus.DEPRECATED,
                        Map.of("RESIDUAL_TRANSFER", Map.of())));

        assertThat(updated.getName()).isEqualTo("Updated FAIR");
        assertThat(updated.getVersion()).isEqualTo("3.1");
        assertThat(updated.getFamily()).isEqualTo(MethodologyFamily.CUSTOM);
        assertThat(updated.getInputSchema()).containsEntry("factor", "schema");
        assertThat(updated.getStatus()).isEqualTo(MethodologyProfileStatus.DEPRECATED);
        assertThat(updated.getTreatmentStrategyVocabulary()).containsKey("RESIDUAL_TRANSFER");
    }

    @Test
    void deleteRemovesResolvedProfile() {
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        when(repository.findByIdAndProjectId(profileId, projectId)).thenReturn(Optional.of(profile));

        service.delete(projectId, profileId);

        verify(repository).delete(profile);
    }
}
