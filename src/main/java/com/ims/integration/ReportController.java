package com.ims.integration;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.ims.common.idempotency.IdempotencyExempt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@RequiresFeature(Feature.CUSTOM_REPORTS)
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public List<ReportService.ReportTemplateView> list(@RequestParam(required = false) String entityType) {
        return reportService.listTemplates(entityType);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN:CREATE')")
    public ReportService.ReportTemplateView create(@Valid @RequestBody ReportService.CreateReportTemplateCommand cmd) {
        return reportService.createTemplate(cmd);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ADMIN:DELETE')")
    public void delete(@PathVariable UUID id) {
        reportService.deleteTemplate(id);
    }

    @IdempotencyExempt(reason = "Read-only PDF render returning a binary payload that cannot be stored or replayed")
    @PostMapping("/{templateId}/render")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public ResponseEntity<byte[]> render(
            @PathVariable UUID templateId,
            @RequestParam(required = false) UUID entityId
    ) {
        byte[] pdf = reportService.renderPdf(templateId, entityId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"report-" + templateId + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
