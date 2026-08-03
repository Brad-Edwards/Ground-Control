package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportData.RequirementSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Serializes requirements to specs-as-code markdown (frontmatter + body), one folder per
 * requirement, for the one-time migration out of the relational store (issue #1500).
 *
 * <p>The frontmatter contract ({@code id}, {@code title}, {@code status}, {@code type},
 * {@code priority}, optional {@code wave}, timestamps) is the deterministic surface the
 * {@code run_requirement_specs_frontmatter_check} policy lint validates; keep the two in step.
 *
 * <p>Security invariant: a requirement UID is untrusted as a filesystem path component. Every
 * folder name is sanitized to {@code [A-Za-z0-9._-]} and the resolved directory is asserted to
 * stay under the supplied output root, so no requirement can cause a write outside it.
 */
@Service
public class RequirementsMarkdownExportService {

    private static final String FILE_NAME = "requirement.md";

    public String toMarkdown(RequirementSnapshot req) {
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(req.uid()).append('\n');
        sb.append("title: \"").append(yamlEscape(nullToEmpty(req.title()))).append("\"\n");
        sb.append("status: ").append(req.status()).append('\n');
        sb.append("type: ").append(req.requirementType()).append('\n');
        sb.append("priority: ").append(req.priority()).append('\n');
        if (req.wave() != null) {
            sb.append("wave: ").append(req.wave()).append('\n');
        }
        if (req.createdAt() != null) {
            sb.append("created_at: ").append(req.createdAt()).append('\n');
        }
        if (req.updatedAt() != null) {
            sb.append("updated_at: ").append(req.updatedAt()).append('\n');
        }
        sb.append("---\n\n");

        sb.append("# ")
                .append(req.uid())
                .append(" — ")
                .append(nullToEmpty(req.title()))
                .append("\n\n");
        sb.append("## Statement\n\n")
                .append(nullToEmpty(req.statement()).strip())
                .append('\n');

        String rationale = req.rationale();
        if (rationale != null && !rationale.isBlank()) {
            sb.append("\n## Rationale\n\n").append(rationale.strip()).append('\n');
        }

        var links = req.traceabilityLinks();
        if (links != null && !links.isEmpty()) {
            sb.append("\n## Traceability\n\n");
            for (var link : links) {
                sb.append("- ")
                        .append(link.linkType())
                        .append(" → ")
                        .append(link.artifactType())
                        .append(" `")
                        .append(nullToEmpty(link.artifactIdentifier()))
                        .append('`');
                if (link.artifactTitle() != null && !link.artifactTitle().isBlank()) {
                    sb.append(" (").append(link.artifactTitle().strip()).append(')');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Reduces a requirement UID to a single safe filesystem folder name. Any character outside
     * {@code [A-Za-z0-9._-]} becomes {@code _}, guaranteeing the result carries no path separator
     * or traversal segment. A UID that is blank or reduces to {@code .} / {@code ..} is rejected
     * rather than silently coerced, because those are not writable folder names.
     */
    public String safeFolderName(String uid) {
        if (uid == null) {
            throw new IllegalArgumentException("requirement uid must not be null");
        }
        String trimmed = uid.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("requirement uid must not be blank");
        }
        String sanitized = trimmed.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("requirement uid does not yield a usable folder name: " + uid);
        }
        return sanitized;
    }

    /**
     * Writes one {@code <uid>/requirement.md} per requirement under {@code outputRoot} and returns
     * the number written. Fails closed if a sanitized folder would ever resolve outside the root.
     */
    public int writeAll(RequirementsExportData data, Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        Set<String> usedFolderNames = new HashSet<>();
        int count = 0;
        for (var req : data.requirements()) {
            String folderName = safeFolderName(req.uid());
            // Distinct UIDs can sanitize to the same folder name; refuse rather than silently
            // overwrite (and still count) an already-exported requirement, which would drop a record.
            if (!usedFolderNames.add(folderName)) {
                throw new IllegalStateException("requirement uid '" + req.uid()
                        + "' collides with an already-exported folder '" + folderName + "'");
            }
            Path folder = root.resolve(folderName).normalize();
            if (!folder.startsWith(root)) {
                throw new IllegalStateException("refusing to write requirement outside the output root");
            }
            // A pre-existing symlink at the write boundary would be followed by createDirectories /
            // writeString and land the file at an attacker-chosen target outside the root. Reject the
            // symlinked component, then re-check containment against the resolved real path.
            if (Files.isSymbolicLink(folder)) {
                throw new IllegalStateException("refusing to write through a symlinked requirement folder: " + folder);
            }
            Files.createDirectories(folder);
            if (!folder.toRealPath().startsWith(realRoot)) {
                throw new IllegalStateException("refusing to write requirement outside the output root");
            }
            Path file = folder.resolve(FILE_NAME);
            if (Files.isSymbolicLink(file)) {
                throw new IllegalStateException("refusing to write through a symlinked requirement file: " + file);
            }
            Files.writeString(file, toMarkdown(req), StandardCharsets.UTF_8);
            count++;
        }
        return count;
    }

    private static String yamlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
