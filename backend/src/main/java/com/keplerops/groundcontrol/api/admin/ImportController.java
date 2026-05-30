package com.keplerops.groundcontrol.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.exception.GroundControlException;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle;
import com.keplerops.groundcontrol.domain.interchange.service.GrcInterchangeImportResult;
import com.keplerops.groundcontrol.domain.interchange.service.GrcInterchangeImporter;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.service.ImportService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/import")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private final ImportService importService;
    private final ProjectService projectService;
    private final GrcInterchangeImporter grcInterchangeImporter;
    private final ObjectMapper objectMapper;

    public ImportController(
            ImportService importService,
            ProjectService projectService,
            GrcInterchangeImporter grcInterchangeImporter,
            ObjectMapper objectMapper) {
        this.importService = importService;
        this.projectService = projectService;
        this.grcInterchangeImporter = grcInterchangeImporter;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/strictdoc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultResponse importStrictdoc(
            @RequestParam("file") MultipartFile file, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        byte[] bytes = readUploadedBytes(file);
        var content = new String(bytes, StandardCharsets.UTF_8);
        var filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.sdoc";
        return ImportResultResponse.from(importService.importStrictdoc(projectId, filename, content));
    }

    @PostMapping(value = "/reqif", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultResponse importReqif(
            @RequestParam("file") MultipartFile file, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        byte[] bytes = readUploadedBytes(file);
        var content = new String(bytes, StandardCharsets.UTF_8);
        var filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.reqif";
        return ImportResultResponse.from(importService.importReqif(projectId, filename, content));
    }

    /**
     * Import a graph-native GRC interchange bundle per GC-P012. JSON only (no
     * XML) — that decision is in ADR-026's XXE-avoidance discussion and
     * narrowed further by this cluster's security note. Multipart upload so
     * large bundles do not hit JSON body limits, but the body is parsed as
     * JSON via the shared {@link ObjectMapper} only.
     */
    @PostMapping(value = "/grc-interchange", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GrcInterchangeImportResult importGrcInterchange(
            @RequestParam("file") MultipartFile file, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        byte[] bytes = readUploadedBytes(file);
        GrcInterchangeBundle bundle;
        try {
            bundle = objectMapper.readValue(bytes, GrcInterchangeBundle.class);
        } catch (IOException e) {
            // Jackson parse messages routinely include input snippets and byte
            // offsets so they reflect attacker-controlled bytes back into the
            // error envelope and into structured logs. Reuse Spring's standard
            // HttpMessageNotReadableException so the existing 400 handler
            // returns the same stable "Malformed request body" envelope it
            // already emits for every other @RequestBody parse failure -- no
            // bespoke 500 envelope, no Jackson detail leakage.
            log.warn("grc_interchange_parse_failed", e);
            throw new HttpMessageNotReadableException(
                    "Malformed GRC interchange bundle", e, new ByteArrayHttpInputMessage(bytes));
        }
        return grcInterchangeImporter.importBundle(projectId, bundle);
    }

    /**
     * Minimal {@link HttpInputMessage} wrapper so we can throw
     * {@link HttpMessageNotReadableException} with the original byte payload
     * preserved for diagnostic purposes without re-reading the multipart.
     * The headers are left empty: the handler only needs the body to satisfy
     * the constructor contract.
     */
    private static final class ByteArrayHttpInputMessage implements HttpInputMessage {
        private final byte[] body;

        ByteArrayHttpInputMessage(byte[] body) {
            this.body = body;
        }

        @Override
        public java.io.InputStream getBody() {
            return new java.io.ByteArrayInputStream(body);
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return new org.springframework.http.HttpHeaders();
        }
    }

    /**
     * Read the multipart payload into bytes with sanitized error handling.
     *
     * <p>Underlying {@link IOException} messages can carry environment or
     * client-controlled detail; surface a stable opaque envelope instead and
     * keep the underlying cause in the application log only. This is the
     * cluster-scoped tightening of the pre-existing strictdoc/reqif paths
     * called out in the security review.
     */
    private static byte[] readUploadedBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            log.warn("uploaded_file_read_failed", e);
            throw new GroundControlException("Failed to read uploaded file", "file_read_error", e);
        }
    }
}
