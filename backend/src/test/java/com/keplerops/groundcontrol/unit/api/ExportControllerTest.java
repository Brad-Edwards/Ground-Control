package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.export.ExportController;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.service.AnalysisService;
import com.keplerops.groundcontrol.domain.requirements.service.AnalysisSweepService;
import com.keplerops.groundcontrol.domain.requirements.service.CompletenessResult;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportCsvService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportExcelService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementsExportPdfService;
import com.keplerops.groundcontrol.domain.requirements.service.SweepExportCsvService;
import com.keplerops.groundcontrol.domain.requirements.service.SweepExportExcelService;
import com.keplerops.groundcontrol.domain.requirements.service.SweepExportPdfService;
import com.keplerops.groundcontrol.domain.requirements.service.SweepReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ExportController.class)
class ExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private AnalysisService analysisService;

    @MockitoBean
    private AnalysisSweepService analysisSweepService;

    @MockitoBean
    private RequirementsExportCsvService requirementsCsvService;

    @MockitoBean
    private RequirementsExportExcelService requirementsExcelService;

    @MockitoBean
    private RequirementsExportPdfService requirementsPdfService;

    @MockitoBean
    private SweepExportCsvService sweepCsvService;

    @MockitoBean
    private SweepExportExcelService sweepExcelService;

    @MockitoBean
    private SweepExportPdfService sweepPdfService;

    @MockitoBean
    private com.keplerops.groundcontrol.domain.documents.service.DocumentExportService documentExportService;

    @MockitoBean
    private com.keplerops.groundcontrol.domain.interchange.service.GrcInterchangeExporter grcInterchangeExporter;

    private static final UUID PROJECT_ID = UUID.randomUUID();

    @Test
    void exportRequirements_csv_returnsCsvResponse() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(projectService.resolveProjectIdentifier(any())).thenReturn("test-project");
        when(analysisService.getRequirementsExportData(eq(PROJECT_ID))).thenReturn(List.of());
        when(requirementsCsvService.toCsv(any())).thenReturn("uid,title\n");

        mockMvc.perform(get("/api/v1/export/requirements"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("requirements")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")));
    }

    @Test
    void exportRequirements_xlsx_returnsExcelResponse() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(projectService.resolveProjectIdentifier(any())).thenReturn("test-project");
        when(analysisService.getRequirementsExportData(eq(PROJECT_ID))).thenReturn(List.of());
        when(requirementsExcelService.toExcel(any())).thenReturn(new byte[] {0x50, 0x4B});

        mockMvc.perform(get("/api/v1/export/requirements").param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")));
    }

    @Test
    void exportRequirements_pdf_returnsPdfResponse() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(projectService.resolveProjectIdentifier(any())).thenReturn("test-project");
        when(analysisService.getRequirementsExportData(eq(PROJECT_ID))).thenReturn(List.of());
        when(requirementsPdfService.toPdf(any())).thenReturn(new byte[] {0x25, 0x50});

        mockMvc.perform(get("/api/v1/export/requirements").param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")));
    }

    @Test
    void exportSweep_csv_returnsCsvResponse() throws Exception {
        var report = new SweepReport(
                "test-project",
                Instant.now(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                new CompletenessResult(0, Map.of(), List.of()),
                null);
        when(analysisSweepService.sweep(any())).thenReturn(report);
        when(sweepCsvService.toCsv(any())).thenReturn("# Sweep Report\n");

        mockMvc.perform(post("/api/v1/export/sweep"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("sweep")));
    }

    @Test
    void exportSweep_xlsx_returnsExcelResponse() throws Exception {
        var report = new SweepReport(
                "test-project",
                Instant.now(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                new CompletenessResult(0, Map.of(), List.of()),
                null);
        when(analysisSweepService.sweep(any())).thenReturn(report);
        when(sweepExcelService.toExcel(any())).thenReturn(new byte[] {0x50, 0x4B});

        mockMvc.perform(post("/api/v1/export/sweep").param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void exportSweep_pdf_returnsPdfResponse() throws Exception {
        var report = new SweepReport(
                "test-project",
                Instant.now(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                new CompletenessResult(0, Map.of(), List.of()),
                null);
        when(analysisSweepService.sweep(any())).thenReturn(report);
        when(sweepPdfService.toPdf(any())).thenReturn(new byte[] {0x25, 0x50});

        mockMvc.perform(post("/api/v1/export/sweep").param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"));
    }

    @Test
    void exportDocument_sdoc_returnsSdocResponse() throws Exception {
        UUID docId = UUID.randomUUID();
        when(documentExportService.exportToSdoc(eq(docId))).thenReturn("[[SECTION]]\nTITLE: Test\n[[/SECTION]]\n");

        mockMvc.perform(get("/api/v1/export/document/" + docId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Disposition", containsString(".sdoc")));
    }

    @Test
    void exportDocument_html_returnsHtmlResponse() throws Exception {
        UUID docId = UUID.randomUUID();
        when(documentExportService.exportToHtml(eq(docId))).thenReturn("<!DOCTYPE html><html></html>");

        mockMvc.perform(get("/api/v1/export/document/" + docId).param("format", "html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Content-Disposition", containsString(".html")));
    }

    @Test
    void exportDocument_pdf_returnsPdfResponse() throws Exception {
        UUID docId = UUID.randomUUID();
        when(documentExportService.exportToPdf(eq(docId))).thenReturn(new byte[] {0x25, 0x50, 0x44, 0x46});

        mockMvc.perform(get("/api/v1/export/document/" + docId).param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString(".pdf")));
    }

    @Test
    void exportDocument_reqif_returnsXmlResponse() throws Exception {
        UUID docId = UUID.randomUUID();
        when(documentExportService.exportToReqif(eq(docId))).thenReturn("<?xml version=\"1.0\"?><REQ-IF/>");

        mockMvc.perform(get("/api/v1/export/document/" + docId).param("format", "reqif"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(header().string("Content-Disposition", containsString(".reqif")));
    }
}
