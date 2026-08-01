package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetSubtypeSchema;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetSubtypeSchemaCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetSubtypeSchemaCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetSubtypeSchemaStatus;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from AssetServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class AssetServiceSubtypeSchemaRegistryTest {
    @Mock
    private OperationalAssetRepository assetRepository;

    @Mock
    private AssetRelationRepository relationRepository;

    @Mock
    private AssetLinkRepository linkRepository;

    @Mock
    private AssetExternalIdRepository externalIdRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository findingLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository auditLinkRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GraphTargetResolverService graphTargetResolverService;

    @Mock
    private AssetSubtypeSchemaRepository subtypeSchemaRepository;

    @org.mockito.Spy
    @SuppressWarnings("UnusedVariable") // Injected into AssetService via @InjectMocks; errorprone misses the wire.
    private AssetSubtypeValidator subtypeValidator = new AssetSubtypeValidator();

    @InjectMocks
    private AssetService assetService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Nested
    class SubtypeSchemaRegistry {

        @Test
        void registerFirstSchemaIsActive() {
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v1",
                    "Cloud service principals",
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING", "required", true))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v1"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.IDENTITY, "service_principal", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(subtypeSchemaRepository.saveAndFlush(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetSubtypeSchema.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var result = assetService.registerSubtypeSchema(command);

            assertThat(result.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.ACTIVE);
            assertThat(result.getSubtype()).isEqualTo("service_principal");
            assertThat(result.getSchemaVersion()).isEqualTo("v1");
        }

        @Test
        void registerSecondActiveDeprecatesPrevious() {
            var existing = new AssetSubtypeSchema(project, AssetType.IDENTITY, "service_principal", "v1", Map.of());
            setField(existing, "id", UUID.randomUUID());

            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v2",
                    null,
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING"))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v2"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.IDENTITY, "service_principal", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.of(existing));
            when(subtypeSchemaRepository.saveAndFlush(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetSubtypeSchema.class);
                if (saved.getId() == null) {
                    setField(saved, "id", UUID.randomUUID());
                }
                return saved;
            });

            var result = assetService.registerSubtypeSchema(command);

            assertThat(existing.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.DEPRECATED);
            assertThat(result.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.ACTIVE);
            // Both writes use saveAndFlush to force ordering: the deprecation
            // UPDATE must hit the DB before the new ACTIVE INSERT, or the
            // partial unique index uk_asset_subtype_schema_active fires
            // against the still-ACTIVE prior row.
            verify(subtypeSchemaRepository, times(2)).saveAndFlush(any());
        }

        @Test
        void registerDuplicateVersionConflicts() {
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v1",
                    null,
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING"))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v1"))
                    .thenReturn(true);

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void registerTranslatesDbUniqueViolationToConflict() {
            // Codex pre-push review: the partial unique index on
            // (project, asset_type, subtype) WHERE status='ACTIVE' (V075) is
            // the safety net for a concurrent race past the service-layer
            // existence check. Spring's DataIntegrityViolationException must
            // surface as ConflictException rather than HTTP 500.
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v1",
                    null,
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING"))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v1"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.IDENTITY, "service_principal", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(subtypeSchemaRepository.saveAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "uk_asset_subtype_schema_active"));

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).getErrorCode())
                    .isEqualTo("asset_subtype_schema_active_conflict");
        }

        @Test
        void registerRejectsMalformedSchemaBody() {
            // Codex pre-push review: a malformed schema body must be rejected at
            // the registry boundary so it cannot block subsequent asset writes.
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId, AssetType.IDENTITY, "service_principal", "v1", null, Map.of("fields", "not-a-map"));

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void registerRejectsBlankSubtype() {
            var command = new CreateAssetSubtypeSchemaCommand(projectId, AssetType.SERVICE, " ", "v1", null, Map.of());

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("subtype");
        }

        @Test
        void deprecateMarksSchemaDeprecated() {
            var schema = new AssetSubtypeSchema(project, AssetType.SERVICE, "internal_api", "v1", Map.of());
            setField(schema, "id", UUID.randomUUID());
            when(subtypeSchemaRepository.findByIdAndProjectId(schema.getId(), projectId))
                    .thenReturn(Optional.of(schema));
            when(subtypeSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assetService.deprecateSubtypeSchema(projectId, schema.getId());

            assertThat(schema.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.DEPRECATED);
        }

        @Test
        void updateRejectsClearSchemaBodyOnActiveRow() {
            // Codex over-cap finding 3: an ACTIVE registry row must keep an
            // enforceable schema body. Callers must deprecate first if the
            // intent is to drop the contract entirely.
            var schema = new AssetSubtypeSchema(
                    project,
                    AssetType.SERVICE,
                    "internal_api",
                    "v1",
                    Map.of("fields", Map.of("name", Map.of("type", "STRING"))));
            setField(schema, "id", UUID.randomUUID());
            when(subtypeSchemaRepository.findByIdAndProjectId(schema.getId(), projectId))
                    .thenReturn(Optional.of(schema));

            var command = new UpdateAssetSubtypeSchemaCommand(null, null, false, /* clearSchemaBody */ true);
            var schemaId = schema.getId();

            assertThatThrownBy(() -> assetService.updateSubtypeSchema(projectId, schemaId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getErrorCode())
                    .isEqualTo("asset_subtype_schema_active_body_required");
        }

        @Test
        void updateReplacesSchemaBody() {
            var schema = new AssetSubtypeSchema(project, AssetType.SERVICE, "internal_api", "v1", Map.of());
            setField(schema, "id", UUID.randomUUID());
            when(subtypeSchemaRepository.findByIdAndProjectId(schema.getId(), projectId))
                    .thenReturn(Optional.of(schema));
            when(subtypeSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetSubtypeSchemaCommand(
                    "New description", Map.of("fields", Map.of("name", Map.of("type", "STRING"))), false, false);

            var result = assetService.updateSubtypeSchema(projectId, schema.getId(), command);

            assertThat(result.getDescription()).isEqualTo("New description");
            assertThat(result.getSchemaBody()).containsKey("fields");
        }

        @Test
        void getActiveThrowsWhenAbsent() {
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.SERVICE, "missing", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> assetService.getActiveSubtypeSchema(projectId, AssetType.SERVICE, "missing"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void listByProjectOnlyReturnsAllProjectSchemas() {
            var s1 = new AssetSubtypeSchema(project, AssetType.SERVICE, "api", "v1", Map.of());
            var s2 = new AssetSubtypeSchema(project, AssetType.IDENTITY, "user_account", "v1", Map.of());
            when(subtypeSchemaRepository.findByProjectId(projectId)).thenReturn(List.of(s1, s2));

            var result = assetService.listSubtypeSchemas(projectId, null, null);

            assertThat(result).hasSize(2);
        }

        @Test
        void listRejectsSubtypeWithoutAssetType() {
            assertThatThrownBy(() -> assetService.listSubtypeSchemas(projectId, null, "rogue"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("assetType");
        }
    }
}
