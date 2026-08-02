package com.keplerops.groundcontrol.unit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.service.AnalysisService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsMarkdownExportService;
import com.keplerops.groundcontrol.shared.cli.RequirementsMarkdownExportRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class RequirementsMarkdownExportRunnerTest {

    private static final int NOT_CALLED = Integer.MIN_VALUE;

    private final ProjectService projectService = mock(ProjectService.class);
    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final RequirementsMarkdownExportService markdownService = new RequirementsMarkdownExportService();

    private int exitCode = NOT_CALLED;

    private RequirementsMarkdownExportRunner runner() {
        return new RequirementsMarkdownExportRunner(
                projectService, analysisService, markdownService, code -> exitCode = code);
    }

    @Test
    void noOpWhenExportFlagAbsent() {
        runner().run(new DefaultApplicationArguments("--project=ground-control"));
        assertThat(exitCode).isEqualTo(NOT_CALLED);
    }

    @Test
    void rejectsMissingOutputDir() {
        runner().run(new DefaultApplicationArguments("--export-requirements", "--project=ground-control"));
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void exportsToOutputRootAndExitsOk(@TempDir Path root) {
        UUID projectId = UUID.randomUUID();
        when(projectService.resolveProjectId("ground-control")).thenReturn(projectId);
        when(projectService.resolveProjectIdentifier("ground-control")).thenReturn("ground-control");
        when(analysisService.getRequirementsExportData(projectId)).thenReturn(List.of());

        runner().run(new DefaultApplicationArguments(
                "--export-requirements", "--project=ground-control", "--output-dir=" + root));

        assertThat(exitCode).isEqualTo(0);
        assertThat(Files.isDirectory(root)).isTrue();
    }

    @Test
    void exitsUserErrorWhenProjectResolutionRejected(@TempDir Path root) {
        when(projectService.resolveProjectId("bad")).thenThrow(new IllegalArgumentException("no such project"));

        runner().run(new DefaultApplicationArguments("--export-requirements", "--project=bad", "--output-dir=" + root));

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void exitsRuntimeErrorWhenExportThrows(@TempDir Path root) {
        UUID projectId = UUID.randomUUID();
        when(projectService.resolveProjectId("ground-control")).thenReturn(projectId);
        when(projectService.resolveProjectIdentifier("ground-control")).thenReturn("ground-control");
        when(analysisService.getRequirementsExportData(projectId)).thenThrow(new RuntimeException("db down"));

        runner().run(new DefaultApplicationArguments(
                "--export-requirements", "--project=ground-control", "--output-dir=" + root));

        assertThat(exitCode).isEqualTo(1);
    }
}
