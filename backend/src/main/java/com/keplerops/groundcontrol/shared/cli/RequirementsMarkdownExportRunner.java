package com.keplerops.groundcontrol.shared.cli;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.service.AnalysisService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportData;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsMarkdownExportService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-time CLI to migrate a project's requirements out of the relational store into specs-as-code
 * markdown (issue #1500). Activated only when the app is started with {@code --export-requirements};
 * otherwise it is a no-op, so it has no effect on normal application boot.
 *
 * <p>Invocation (Flyway disabled so the read-only export never mutates schema; a throwaway port
 * so it never contends with a running instance):
 *
 * <pre>
 * ./gradlew bootRun --args='--export-requirements --project=ground-control \
 *     --output-dir=docs/requirements --spring.flyway.enabled=false \
 *     --spring.jpa.hibernate.ddl-auto=none --server.port=0'
 * </pre>
 *
 * <p>It reuses the existing read/serialization path ({@link AnalysisService#getRequirementsExportData}
 * → {@link RequirementsExportData}) rather than issuing its own SQL, and writes one safe folder per
 * requirement under the maintainer-supplied output root (path containment enforced by
 * {@link RequirementsMarkdownExportService}). Diagnostics log project identity, count, and output
 * root only — never requirement bodies. After a successful (or failed) one-shot it exits the JVM,
 * mirroring {@code FirstAdminBootstrapRunner}: falling through into a normally running server is not
 * the intent of a migration command.
 */
@Component
public class RequirementsMarkdownExportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RequirementsMarkdownExportRunner.class);

    private static final String EXPORT_OPTION = "export-requirements";
    private static final String PROJECT_OPTION = "project";
    private static final String OUTPUT_DIR_OPTION = "output-dir";

    private static final int EXIT_OK = 0;
    private static final int EXIT_RUNTIME_ERROR = 1;
    private static final int EXIT_USER_ERROR = 2;

    /** Exit shim so unit tests can record calls instead of terminating the JVM. */
    @FunctionalInterface
    public interface ExportExit {
        void exit(int code);
    }

    private final ProjectService projectService;
    private final AnalysisService analysisService;
    private final RequirementsMarkdownExportService markdownService;
    private final ExportExit exit;

    @Autowired
    public RequirementsMarkdownExportRunner(
            ProjectService projectService,
            AnalysisService analysisService,
            RequirementsMarkdownExportService markdownService) {
        this(projectService, analysisService, markdownService, System::exit);
    }

    public RequirementsMarkdownExportRunner(
            ProjectService projectService,
            AnalysisService analysisService,
            RequirementsMarkdownExportService markdownService,
            ExportExit exit) {
        this.projectService = projectService;
        this.analysisService = analysisService;
        this.markdownService = markdownService;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(EXPORT_OPTION)) {
            return;
        }
        String outputDir = firstOption(args, OUTPUT_DIR_OPTION);
        if (outputDir == null || outputDir.isBlank()) {
            log.error("--export-requirements requires --output-dir=<path>");
            exit.exit(EXIT_USER_ERROR);
            return;
        }
        // Null project defers to ProjectService, which resolves the sole project or fails clearly —
        // the same contract the export REST endpoint relies on.
        String project = firstOption(args, PROJECT_OPTION);
        try {
            UUID projectId = projectService.resolveProjectId(project);
            String projectIdentifier = projectService.resolveProjectIdentifier(project);
            var data = RequirementsExportData.from(
                    projectIdentifier, analysisService.getRequirementsExportData(projectId));
            int count = markdownService.writeAll(data, Path.of(outputDir));
            log.info("Exported {} requirements for project '{}' to {}", count, projectIdentifier, outputDir);
            exit.exit(EXIT_OK);
        } catch (IllegalArgumentException e) {
            log.error("Requirements export rejected: {}", e.getMessage());
            exit.exit(EXIT_USER_ERROR);
        } catch (Exception e) {
            log.error("Requirements export failed: {}", e.getMessage());
            exit.exit(EXIT_RUNTIME_ERROR);
        }
    }

    private static String firstOption(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
