package com.keplerops.groundcontrol.unit.domain.compliance;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.repository.ComplianceFrameworkMappingRepository;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceFrameworkMappingService;
import com.keplerops.groundcontrol.domain.compliance.service.CreateComplianceFrameworkMappingCommand;
import com.keplerops.groundcontrol.domain.compliance.service.UpdateComplianceFrameworkMappingCommand;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for ComplianceFrameworkMappingService (GC-I002 / GC-I005 / GC-I007 / GC-L011). */
@ExtendWith(MockitoExtension.class)
class ComplianceFrameworkMappingServiceTest {

    @Mock
    private ComplianceFrameworkMappingRepository repository;

    @Mock
    private ProjectService projectService;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private ControlRepository controlRepository;

    @InjectMocks
    private ComplianceFrameworkMappingService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REQUIREMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

    private Project project;
    private Requirement requirement;
    private Control control;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);

        requirement = new Requirement(project, "GC-Q001", "Some requirement", "Statement");
        setField(requirement, "id", REQUIREMENT_ID);

        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        setField(control, "id", CONTROL_ID);
    }

    @Nested
    class Create {

        @Test
        void requirementSide_persistsAggregate() {
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(requirementRepository.findByIdAndProjectId(REQUIREMENT_ID, PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var saved = service.create(new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    REQUIREMENT_ID,
                    null,
                    ComplianceFrameworkIdentifier.SOC2,
                    null,
                    "2017 TSC",
                    "CC1.1",
                    CoverageLevel.PARTIAL,
                    "Documented governance"));

            assertThat(saved.getRequirement()).isEqualTo(requirement);
            assertThat(saved.getControl()).isNull();
            assertThat(saved.getFramework()).isEqualTo(ComplianceFrameworkIdentifier.SOC2);
            assertThat(saved.getFrameworkElement()).isEqualTo("CC1.1");
            assertThat(saved.getCoverageLevel()).isEqualTo(CoverageLevel.PARTIAL);
            assertThat(saved.isRequirementSide()).isTrue();
        }

        @Test
        void controlSide_persistsAggregate() {
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(CONTROL_ID, PROJECT_ID)).thenReturn(Optional.of(control));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var saved = service.create(new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    null,
                    CONTROL_ID,
                    ComplianceFrameworkIdentifier.ISO_27001,
                    null,
                    "2022",
                    "A.5.1",
                    CoverageLevel.FULL,
                    null));

            assertThat(saved.getControl()).isEqualTo(control);
            assertThat(saved.getRequirement()).isNull();
            assertThat(saved.isControlSide()).isTrue();
        }

        @Test
        void bothEndpoints_throwsDomainValidation() {
            var cmd = new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    REQUIREMENT_ID,
                    CONTROL_ID,
                    ComplianceFrameworkIdentifier.SOC2,
                    null,
                    null,
                    "CC1.1",
                    CoverageLevel.PARTIAL,
                    null);

            assertThatThrownBy(() -> service.create(cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("Exactly one");

            verify(repository, never()).save(any());
        }

        @Test
        void neitherEndpoint_throwsDomainValidation() {
            var cmd = new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    null,
                    null,
                    ComplianceFrameworkIdentifier.SOC2,
                    null,
                    null,
                    "CC1.1",
                    CoverageLevel.PARTIAL,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void missingFramework_throwsDomainValidation() {
            var cmd = new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID, REQUIREMENT_ID, null, null, null, null, "CC1.1", CoverageLevel.PARTIAL, null);

            assertThatThrownBy(() -> service.create(cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("framework");
        }

        @Test
        void blankElement_throwsDomainValidation() {
            var cmd = new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    REQUIREMENT_ID,
                    null,
                    ComplianceFrameworkIdentifier.SOC2,
                    null,
                    null,
                    "   ",
                    CoverageLevel.PARTIAL,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void controlNotInProject_throwsNotFound() {
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(CONTROL_ID, PROJECT_ID)).thenReturn(Optional.empty());

            var cmd = new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    null,
                    CONTROL_ID,
                    ComplianceFrameworkIdentifier.SOC2,
                    null,
                    null,
                    "CC1.1",
                    CoverageLevel.PARTIAL,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void duplicateRequirementTuple_throwsConflict() {
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(requirementRepository.findByIdAndProjectId(REQUIREMENT_ID, PROJECT_ID))
                    .thenReturn(Optional.of(requirement));
            when(repository.existsByRequirementIdAndFrameworkAndFrameworkElement(
                            REQUIREMENT_ID, ComplianceFrameworkIdentifier.SOC2, "CC1.1"))
                    .thenReturn(true);

            var cmd = new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    REQUIREMENT_ID,
                    null,
                    ComplianceFrameworkIdentifier.SOC2,
                    null,
                    null,
                    "CC1.1",
                    CoverageLevel.PARTIAL,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
        }

        @Test
        void externalIdentifierWithNewline_throwsDomainValidation() {
            // Log-injection guard from the cluster security note.
            var cmd = new CreateComplianceFrameworkMappingCommand(
                    PROJECT_ID,
                    REQUIREMENT_ID,
                    null,
                    ComplianceFrameworkIdentifier.SOC2,
                    "Custom\nIdentifier",
                    null,
                    "CC1.1",
                    CoverageLevel.PARTIAL,
                    null);

            assertThatThrownBy(() -> service.create(cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("control characters");
        }
    }

    @Nested
    class Update {

        @Test
        void partialUpdate_applies() {
            var mapping = ComplianceFrameworkMapping.forRequirement(
                    project, requirement, ComplianceFrameworkIdentifier.SOC2, "CC1.1", CoverageLevel.PARTIAL);
            setField(mapping, "id", MAPPING_ID);
            when(repository.findByIdAndProjectId(MAPPING_ID, PROJECT_ID)).thenReturn(Optional.of(mapping));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var updated = service.update(new UpdateComplianceFrameworkMappingCommand(
                    PROJECT_ID, MAPPING_ID, null, null, null, null, CoverageLevel.FULL, "Now satisfies fully"));

            assertThat(updated.getCoverageLevel()).isEqualTo(CoverageLevel.FULL);
            assertThat(updated.getRationale()).isEqualTo("Now satisfies fully");
            // Framework and element unchanged
            assertThat(updated.getFrameworkElement()).isEqualTo("CC1.1");
        }

        @Test
        void renameWithConflict_throwsConflict() {
            var mapping = ComplianceFrameworkMapping.forRequirement(
                    project, requirement, ComplianceFrameworkIdentifier.SOC2, "CC1.1", CoverageLevel.PARTIAL);
            setField(mapping, "id", MAPPING_ID);
            when(repository.findByIdAndProjectId(MAPPING_ID, PROJECT_ID)).thenReturn(Optional.of(mapping));
            when(repository.existsByRequirementIdAndFrameworkAndFrameworkElement(
                            REQUIREMENT_ID, ComplianceFrameworkIdentifier.SOC2, "CC2.1"))
                    .thenReturn(true);

            var cmd = new UpdateComplianceFrameworkMappingCommand(
                    PROJECT_ID, MAPPING_ID, null, null, null, "CC2.1", null, null);

            assertThatThrownBy(() -> service.update(cmd)).isInstanceOf(ConflictException.class);
        }

        /**
         * The update path must enforce the control-character log-injection guard
         * symmetrically with the create path. Verify each control-character class
         * (newline, carriage return, tab) is rejected with a validation error.
         *
         * <p>Cluster-744 fix (findings #3 / #5): the update path previously routed
         * frameworkIdentifier through sanitizeExternalIdentifier (trim-only), bypassing
         * the guard. A caller could PUT an identifier with embedded newlines and have
         * them land in the entity and from there into structured log lines.
         */
        @ParameterizedTest(name = "externalIdentifier with {0} is rejected")
        @MethodSource("controlCharIdentifiers")
        void externalIdentifierWithControlChar_throwsDomainValidation(String label, String badIdentifier) {
            var mapping = ComplianceFrameworkMapping.forRequirement(
                    project, requirement, ComplianceFrameworkIdentifier.SOC2, "CC1.1", CoverageLevel.PARTIAL);
            setField(mapping, "id", MAPPING_ID);
            when(repository.findByIdAndProjectId(MAPPING_ID, PROJECT_ID)).thenReturn(Optional.of(mapping));

            var cmd = new UpdateComplianceFrameworkMappingCommand(
                    PROJECT_ID, MAPPING_ID, null, badIdentifier, null, null, null, null);

            assertThatThrownBy(() -> service.update(cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("control characters");
        }

        static Stream<Arguments> controlCharIdentifiers() {
            return Stream.of(
                    Arguments.of("newline (\\n)", "Acme\nINJECT"),
                    Arguments.of("carriage return (\\r)", "Acme\rINJECT"),
                    Arguments.of("tab (\\t)", "Acme\tINJECT"));
        }
    }

    @Nested
    class Delete {

        @Test
        void delete_invokesRepoDelete() {
            var mapping = ComplianceFrameworkMapping.forRequirement(
                    project, requirement, ComplianceFrameworkIdentifier.SOC2, "CC1.1", CoverageLevel.PARTIAL);
            setField(mapping, "id", MAPPING_ID);
            when(repository.findByIdAndProjectId(MAPPING_ID, PROJECT_ID)).thenReturn(Optional.of(mapping));

            service.delete(PROJECT_ID, MAPPING_ID);

            verify(repository).delete(mapping);
        }

        @Test
        void deleteMissing_throwsNotFound() {
            when(repository.findByIdAndProjectId(MAPPING_ID, PROJECT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(PROJECT_ID, MAPPING_ID)).isInstanceOf(NotFoundException.class);
        }
    }
}
