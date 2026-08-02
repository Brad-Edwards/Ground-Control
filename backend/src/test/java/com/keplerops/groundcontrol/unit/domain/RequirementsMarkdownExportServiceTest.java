package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportData;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportData.RequirementSnapshot;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportData.TraceabilityLinkSnapshot;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsMarkdownExportService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequirementsMarkdownExportServiceTest {

    private final RequirementsMarkdownExportService service = new RequirementsMarkdownExportService();

    private static RequirementSnapshot full(String uid) {
        return new RequirementSnapshot(
                uid,
                "Console shell: design system",
                "The console MUST present a coherent design system.",
                "Fragmented UI increases operator error.",
                "FUNCTIONAL",
                "MUST",
                "ACTIVE",
                3,
                List.of(new TraceabilityLinkSnapshot(
                        "CODE", "src/App.tsx", "IMPLEMENTS", "https://example/App.tsx", "App shell")),
                Instant.parse("2026-01-02T03:04:05Z"),
                Instant.parse("2026-01-03T04:05:06Z"));
    }

    private static RequirementSnapshot minimal(String uid) {
        return new RequirementSnapshot(
                uid, "Bare", "Only a statement.", "", "CONSTRAINT", "COULD", "DRAFT", null, List.of(), null, null);
    }

    @Test
    void toMarkdown_emitsFrontmatterAndBody() {
        String md = service.toMarkdown(full("GC-Q015"));

        assertThat(md)
                .startsWith("---\n")
                .contains(
                        "\nid: GC-Q015\n",
                        "title: \"Console shell: design system\"",
                        "\nstatus: ACTIVE\n",
                        "\ntype: FUNCTIONAL\n",
                        "\npriority: MUST\n",
                        "\nwave: 3\n",
                        "created_at: 2026-01-02T03:04:05Z",
                        "updated_at: 2026-01-03T04:05:06Z",
                        "## Statement",
                        "The console MUST present a coherent design system.",
                        "## Rationale",
                        "Fragmented UI increases operator error.",
                        "## Traceability",
                        "IMPLEMENTS",
                        "CODE",
                        "src/App.tsx");
    }

    @Test
    void toMarkdown_omitsOptionalSectionsWhenAbsent() {
        String md = service.toMarkdown(minimal("GC-X001"));

        assertThat(md)
                .doesNotContain("wave:", "## Rationale", "## Traceability")
                .contains("## Statement");
    }

    @Test
    void toMarkdown_quotesTitleSafelyWhenItContainsQuotes() {
        var req = new RequirementSnapshot(
                "GC-X002", "A \"quoted\" title", "s", "", "FUNCTIONAL", "MUST", "ACTIVE", null, List.of(), null, null);
        String md = service.toMarkdown(req);
        assertThat(md).contains("title: \"A \\\"quoted\\\" title\"");
    }

    @Test
    void safeFolderName_preservesRealUidAndSanitizesUnsafeChars() {
        assertThat(service.safeFolderName("GC-Q015")).isEqualTo("GC-Q015");
        assertThat(service.safeFolderName("gc x/001")).isEqualTo("gc_x_001");
        assertThat(service.safeFolderName("../escape")).isEqualTo(".._escape");
    }

    @Test
    void safeFolderName_rejectsUnusableComponents() {
        assertThatThrownBy(() -> service.safeFolderName("..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.safeFolderName(".")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.safeFolderName("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.safeFolderName(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeAll_writesOneFolderPerRequirement(@TempDir Path root) throws IOException {
        var data = new RequirementsExportData(
                "ground-control", Instant.parse("2026-01-01T00:00:00Z"), List.of(full("GC-Q015"), minimal("GC-X001")));

        int count = service.writeAll(data, root);

        assertThat(count).isEqualTo(2);
        assertThat(Files.readString(root.resolve("GC-Q015/requirement.md"))).contains("id: GC-Q015");
        assertThat(Files.readString(root.resolve("GC-X001/requirement.md"))).contains("id: GC-X001");
    }

    @Test
    void writeAll_neverEscapesOutputRoot(@TempDir Path root) throws IOException {
        var data = new RequirementsExportData(
                "ground-control", Instant.parse("2026-01-01T00:00:00Z"), List.of(full("../escape")));

        service.writeAll(data, root);

        // A path-traversal UID is contained: the sanitized folder lands under root, and nothing
        // escapes to the parent's "escape" target.
        assertThat(Files.exists(root.resolve(".._escape/requirement.md"))).isTrue();
        assertThat(Files.exists(root.getParent().resolve("escape/requirement.md")))
                .isFalse();
    }

    @Test
    void writeAll_failsLoudlyOnSanitizedFolderCollision(@TempDir Path root) {
        // Distinct UIDs that sanitize to the same folder name must not silently overwrite one
        // another — a migration that drops a record is worse than one that fails.
        var data = new RequirementsExportData(
                "ground-control", Instant.parse("2026-01-01T00:00:00Z"), List.of(minimal("GC/A"), minimal("GC?A")));

        assertThatThrownBy(() -> service.writeAll(data, root)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void writeAll_refusesToWriteThroughSymlinkedRequirementFolder(@TempDir Path root, @TempDir Path outside)
            throws IOException {
        // `outside` is a separate managed temp dir (not under `root`); a symlinked <uid> folder that
        // points there must be refused, not followed.
        Files.createSymbolicLink(root.resolve("GC-X001"), outside);
        var data = new RequirementsExportData(
                "ground-control", Instant.parse("2026-01-01T00:00:00Z"), List.of(minimal("GC-X001")));

        assertThatThrownBy(() -> service.writeAll(data, root)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(outside.resolve("requirement.md"))).isFalse();
    }
}
