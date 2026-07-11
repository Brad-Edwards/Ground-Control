package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.packregistry.service.EmptyPackRegistrationContent;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportFormat;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportOptions;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryImportService;
import com.keplerops.groundcontrol.domain.packregistry.service.PackRegistryService;
import com.keplerops.groundcontrol.domain.packregistry.service.RegisterPackCommand;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PackRegistryImportServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final PackRegistryImportService service =
            new PackRegistryImportService(new ObjectMapper().findAndRegisterModules(), mock(PackRegistryService.class));

    @Test
    void manifestImportHonorsOverrides() {
        var json =
                """
                {
                  "packId": "upstream-pack",
                  "packType": "REQUIREMENTS_PACK",
                  "version": "1.0.0",
                  "publisher": "Upstream"
                }
                """;

        var command = service.toRegisterCommand(
                PROJECT_ID,
                "manifest.json",
                json.getBytes(StandardCharsets.UTF_8),
                new PackRegistryImportOptions(
                        PackRegistryImportFormat.GC_MANIFEST,
                        "override-pack",
                        "2.0.0",
                        "Override Publisher",
                        null,
                        "https://example.test/source.json",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertThat(command.packId()).isEqualTo("override-pack");
        assertThat(command.packType()).isEqualTo(PackType.REQUIREMENTS_PACK);
        assertThat(command.version()).isEqualTo("2.0.0");
        assertThat(command.publisher()).isEqualTo("Override Publisher");
        assertThat(command.sourceUrl()).isEqualTo("https://example.test/source.json");
        assertThat(command.registrationContent()).isEqualTo(EmptyPackRegistrationContent.INSTANCE);
    }

    @Test
    void importEntryDelegatesRegistrationToRegistryService() {
        var registryService = mock(PackRegistryService.class);
        var importService = new PackRegistryImportService(new ObjectMapper().findAndRegisterModules(), registryService);
        var expected = mock(com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry.class);
        when(registryService.registerEntry(any(RegisterPackCommand.class))).thenReturn(expected);

        var result = importService.importEntry(
                PROJECT_ID,
                "manifest.json",
                """
                {
                  "packId": "demo-pack",
                  "packType": "REQUIREMENTS_PACK",
                  "version": "1.0.0"
                }
                """
                        .getBytes(StandardCharsets.UTF_8),
                new PackRegistryImportOptions(
                        PackRegistryImportFormat.AUTO,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        var commandCaptor = ArgumentCaptor.forClass(RegisterPackCommand.class);
        verify(registryService).registerEntry(commandCaptor.capture());
        assertThat(commandCaptor.getValue().packId()).isEqualTo("demo-pack");
        assertThat(result).isSameAs(expected);
    }

    @Test
    void autoDetectImportRejectsUnknownJsonShape() {
        var json = "{\"hello\":\"world\"}";
        var options = defaultOptions(PackRegistryImportFormat.AUTO);

        assertThatThrownBy(() -> toRegisterCommand("unknown.json", json, options))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Could not detect import format");
    }

    @Test
    void rejectsInvalidJsonInput() {
        var json = "{not-json}";
        var options = defaultOptions(PackRegistryImportFormat.AUTO);

        assertThatThrownBy(() -> toRegisterCommand("broken.json", json, options))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Import file must be valid JSON");
    }

    @Test
    void manifestImportParsesDependenciesAndCompatibility() {
        var json =
                """
                {
                  "packId": "source-pack",
                  "packType": "REQUIREMENTS_PACK",
                  "version": "1.0.0",
                  "dependencies": [{"packId": "base-pack", "versionConstraint": "^2.0.0"}]
                }
                """;

        var command = service.toRegisterCommand(
                PROJECT_ID,
                "manifest.json",
                json.getBytes(StandardCharsets.UTF_8),
                new PackRegistryImportOptions(
                        PackRegistryImportFormat.GC_MANIFEST,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("minVersion", "1.0.0"),
                        null,
                        null,
                        null));

        assertThat(command.dependencies()).singleElement().satisfies(dep -> {
            assertThat(dep.packId()).isEqualTo("base-pack");
            assertThat(dep.versionConstraint()).isEqualTo("^2.0.0");
        });
        assertThat(command.compatibility()).containsEntry("minVersion", "1.0.0");
        assertThat(command.registrationContent()).isEqualTo(EmptyPackRegistrationContent.INSTANCE);
    }

    @Test
    void manifestImportRejectsInvalidDependencyShape() {
        var badDependencyJson =
                """
                {
                  "packId": "source-pack",
                  "packType": "REQUIREMENTS_PACK",
                  "version": "1.0.0",
                  "dependencies": [{"versionConstraint": "^1.0.0"}]
                }
                """;
        var options = defaultOptions(PackRegistryImportFormat.GC_MANIFEST);

        assertThatThrownBy(() -> toRegisterCommand("bad-deps.json", badDependencyJson, options))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Each dependency must include packId");
    }

    @Test
    void manifestImportRejectsMissingPackIdAndVersion() {
        var options = defaultOptions(PackRegistryImportFormat.GC_MANIFEST);

        assertThatThrownBy(() -> toRegisterCommand(
                        "missing-packid.json", "{\"packType\":\"REQUIREMENTS_PACK\",\"version\":\"1.0.0\"}", options))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("missing packId");

        assertThatThrownBy(() -> toRegisterCommand(
                        "missing-version.json",
                        "{\"packId\":\"source-pack\",\"packType\":\"REQUIREMENTS_PACK\"}",
                        options))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("missing version");
    }

    private RegisterPackCommand toRegisterCommand(String filename, String json, PackRegistryImportOptions options) {
        return service.toRegisterCommand(PROJECT_ID, filename, json.getBytes(StandardCharsets.UTF_8), options);
    }

    private PackRegistryImportOptions defaultOptions(PackRegistryImportFormat format) {
        return new PackRegistryImportOptions(format, null, null, null, null, null, null, null, null, null, null, null);
    }
}
