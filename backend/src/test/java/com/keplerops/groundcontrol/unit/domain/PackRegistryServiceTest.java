package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.repository.PackRegistryEntryRepository;
import com.keplerops.groundcontrol.domain.packregistry.service.CustomPackTypeHandler;
import com.keplerops.groundcontrol.domain.packregistry.service.EmptyPackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityVerifier;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistrySecurityProperties;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryService;
import com.keplerops.groundcontrol.domain.packregistry.service.PackTypeHandlerRegistry;
import com.keplerops.groundcontrol.domain.packregistry.service.RegisterPackCommand;
import com.keplerops.groundcontrol.domain.packregistry.service.RequirementsPackTypeHandler;
import com.keplerops.groundcontrol.domain.packregistry.service.UpdatePackRegistryEntryCommand;
import com.keplerops.groundcontrol.domain.packregistry.state.CatalogStatus;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackRegistryServiceTest {

    /** A non-empty content marker used only to exercise a metadata-only handler's rejection path. */
    private record NonEmptyContent() implements PackRegistrationContent {}

    @Mock
    private PackRegistryEntryRepository registryRepository;

    @Mock
    private ProjectService projectService;

    private PackRegistryService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Project makeProject() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    @BeforeEach
    void setUp() {
        service = new PackRegistryService(
                registryRepository,
                projectService,
                new PackIntegrityVerifier(new PackRegistrySecurityProperties()),
                new PackTypeHandlerRegistry(List.of(new RequirementsPackTypeHandler(), new CustomPackTypeHandler())));
    }

    @Nested
    class Register {

        @Test
        void registersNewEntry() {
            var project = makeProject();
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(registryRepository.existsByProjectIdAndPackIdAndVersion(PROJECT_ID, "requirements-pack", "1.0.0"))
                    .thenReturn(false);
            when(registryRepository.save(any(PackRegistryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new RegisterPackCommand(
                    PROJECT_ID,
                    "requirements-pack",
                    PackType.REQUIREMENTS_PACK,
                    "1.0.0",
                    "Acme",
                    "Requirements catalog",
                    null,
                    null,
                    null,
                    null,
                    null,
                    EmptyPackRegistrationContent.INSTANCE,
                    null,
                    null);

            var result = service.registerEntry(command);
            assertThat(result.getPackId()).isEqualTo("requirements-pack");
            assertThat(result.getVersion()).isEqualTo("1.0.0");
            assertThat(result.getPackType()).isEqualTo(PackType.REQUIREMENTS_PACK);
            assertThat(result.getCatalogStatus()).isEqualTo(CatalogStatus.AVAILABLE);
            assertThat(result.getChecksum()).isNull();
            verify(registryRepository).save(any(PackRegistryEntry.class));
        }

        @Test
        void rejectsDuplicateVersion() {
            var project = makeProject();
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(registryRepository.existsByProjectIdAndPackIdAndVersion(PROJECT_ID, "requirements-pack", "1.0.0"))
                    .thenReturn(true);

            var command = new RegisterPackCommand(
                    PROJECT_ID,
                    "requirements-pack",
                    PackType.REQUIREMENTS_PACK,
                    "1.0.0",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    EmptyPackRegistrationContent.INSTANCE,
                    null,
                    null);

            assertThatThrownBy(() -> service.registerEntry(command)).isInstanceOf(ConflictException.class);
        }

        @Test
        void registersCustomPackWithoutTypedContent() {
            var project = makeProject();
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(registryRepository.existsByProjectIdAndPackIdAndVersion(PROJECT_ID, "custom-docs", "1.0.0"))
                    .thenReturn(false);
            when(registryRepository.save(any(PackRegistryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new RegisterPackCommand(
                    PROJECT_ID,
                    "custom-docs",
                    PackType.CUSTOM,
                    "1.0.0",
                    "Docs Team",
                    "Metadata-only custom pack",
                    null,
                    null,
                    null,
                    null,
                    null,
                    EmptyPackRegistrationContent.INSTANCE,
                    null,
                    null);

            var result = service.registerEntry(command);
            assertThat(result.getPackType()).isEqualTo(PackType.CUSTOM);
        }

        @Test
        void rejectsMetadataOnlyPackTypeWithTypedContent() {
            var project = makeProject();
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(registryRepository.existsByProjectIdAndPackIdAndVersion(PROJECT_ID, "requirements-pack", "1.0.0"))
                    .thenReturn(false);

            var command = new RegisterPackCommand(
                    PROJECT_ID,
                    "requirements-pack",
                    PackType.REQUIREMENTS_PACK,
                    "1.0.0",
                    "Acme",
                    "Requirements catalog",
                    null,
                    null,
                    null,
                    null,
                    null,
                    new NonEmptyContent(),
                    null,
                    null);

            assertThatThrownBy(() -> service.registerEntry(command)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void rejectsStaleChecksum() {
            var project = makeProject();
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(registryRepository.existsByProjectIdAndPackIdAndVersion(PROJECT_ID, "requirements-pack", "1.0.0"))
                    .thenReturn(false);

            var command = new RegisterPackCommand(
                    PROJECT_ID,
                    "requirements-pack",
                    PackType.REQUIREMENTS_PACK,
                    "1.0.0",
                    "Acme",
                    "Requirements catalog",
                    null,
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                    null,
                    null,
                    null,
                    EmptyPackRegistrationContent.INSTANCE,
                    null,
                    null);

            assertThatThrownBy(() -> service.registerEntry(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("checksum mismatch");
        }

        @Test
        void normalizesDeclaredChecksumWhenItMatches() {
            var project = makeProject();
            when(projectService.getById(PROJECT_ID)).thenReturn(project);
            when(registryRepository.existsByProjectIdAndPackIdAndVersion(PROJECT_ID, "requirements-pack", "1.0.0"))
                    .thenReturn(false);
            when(registryRepository.save(any(PackRegistryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            var verifier = new PackIntegrityVerifier(new PackRegistrySecurityProperties());
            var checksumEntry =
                    new PackRegistryEntry(project, "requirements-pack", PackType.REQUIREMENTS_PACK, "1.0.0");
            checksumEntry.setPublisher("Acme");
            checksumEntry.setDescription("Requirements catalog");
            var expectedChecksum = verifier.verify(checksumEntry).verifiedChecksum();

            var command = new RegisterPackCommand(
                    PROJECT_ID,
                    "requirements-pack",
                    PackType.REQUIREMENTS_PACK,
                    "1.0.0",
                    "Acme",
                    "Requirements catalog",
                    null,
                    expectedChecksum.toUpperCase(Locale.ROOT),
                    null,
                    null,
                    null,
                    EmptyPackRegistrationContent.INSTANCE,
                    null,
                    null);

            var result = service.registerEntry(command);
            assertThat(result.getChecksum()).isEqualTo(expectedChecksum);
        }
    }

    @Nested
    class Withdraw {

        @Test
        void withdrawsEntry() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "requirements-pack", PackType.REQUIREMENTS_PACK, "1.0.0");
            setField(entry, "id", UUID.randomUUID());
            when(registryRepository.findByProjectIdAndPackIdAndVersion(PROJECT_ID, "requirements-pack", "1.0.0"))
                    .thenReturn(Optional.of(entry));
            when(registryRepository.save(any(PackRegistryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.withdrawEntry(PROJECT_ID, "requirements-pack", "1.0.0");
            assertThat(result.getCatalogStatus()).isEqualTo(CatalogStatus.WITHDRAWN);
        }
    }

    @Nested
    class Find {

        @Test
        void findsExistingEntry() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "requirements-pack", PackType.REQUIREMENTS_PACK, "1.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndVersion(PROJECT_ID, "requirements-pack", "1.0.0"))
                    .thenReturn(Optional.of(entry));

            var result = service.findEntry(PROJECT_ID, "requirements-pack", "1.0.0");
            assertThat(result.getPackId()).isEqualTo("requirements-pack");
        }

        @Test
        void throwsNotFoundForMissingEntry() {
            when(registryRepository.findByProjectIdAndPackIdAndVersion(PROJECT_ID, "missing", "1.0.0"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findEntry(PROJECT_ID, "missing", "1.0.0"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListEntries {

        @Test
        void listsAllEntriesForProject() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "requirements-pack", PackType.REQUIREMENTS_PACK, "1.0.0");
            when(registryRepository.findByProjectIdOrderByRegisteredAtDesc(PROJECT_ID))
                    .thenReturn(List.of(entry));

            var results = service.listEntries(PROJECT_ID);
            assertThat(results).hasSize(1);
        }

        @Test
        void listsEntriesByType() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "requirements-pack", PackType.REQUIREMENTS_PACK, "1.0.0");
            when(registryRepository.findByProjectIdAndPackTypeOrderByRegisteredAtDesc(
                            PROJECT_ID, PackType.REQUIREMENTS_PACK))
                    .thenReturn(List.of(entry));

            var results = service.listEntries(PROJECT_ID, PackType.REQUIREMENTS_PACK);
            assertThat(results).hasSize(1);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesExistingEntry() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "requirements-pack", PackType.REQUIREMENTS_PACK, "1.0.0");
            var entryId = UUID.randomUUID();
            setField(entry, "id", entryId);
            when(registryRepository.findById(entryId)).thenReturn(Optional.of(entry));
            when(registryRepository.save(any(PackRegistryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdatePackRegistryEntryCommand(
                    "Updated Publisher", "New description", null, null, null, null, null, null, null, null);

            var result = service.updateEntry(entryId, command);
            assertThat(result.getPublisher()).isEqualTo("Updated Publisher");
            assertThat(result.getDescription()).isEqualTo("New description");
        }
    }
}
