package com.ims.integration;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.context.MapValueResolver;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.integration.spi.ReportDataProvider;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiresFeature(Feature.CUSTOM_REPORTS)
public class ReportService {

    public record ReportTemplateView(
            UUID id,
            String name,
            String entityType,
            String templateHtml,
            String pageSize,
            boolean enabled,
            Instant createdAt
    ) {}

    public record CreateReportTemplateCommand(
            String name,
            String entityType,
            String templateHtml,
            String pageSize
    ) {}

    private final ReportTemplateRepository templateRepository;
    private final Map<String, ReportDataProvider> dataProviders;
    private final Handlebars handlebars;

    public ReportService(
            ReportTemplateRepository templateRepository,
            List<ReportDataProvider> providers
    ) {
        this.templateRepository = templateRepository;
        this.dataProviders = new HashMap<>();
        for (ReportDataProvider provider : providers) {
            this.dataProviders.put(provider.entityType(), provider);
        }
        this.handlebars = new Handlebars();
    }

    @Transactional(readOnly = true)
    public List<ReportTemplateView> listTemplates(String entityType) {
        List<ReportTemplate> list = (entityType != null && !entityType.isBlank())
                ? templateRepository.findByEntityTypeOrderByNameAsc(entityType.trim().toUpperCase(Locale.ROOT))
                : templateRepository.findByOrderByNameAsc();
        return list.stream().map(this::toView).toList();
    }

    @Transactional
    public ReportTemplateView createTemplate(CreateReportTemplateCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank() || cmd.templateHtml() == null || cmd.templateHtml().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and templateHtml are required");
        }
        ReportTemplate tpl = new ReportTemplate();
        tpl.setName(cmd.name().trim());
        tpl.setEntityType(cmd.entityType() != null ? cmd.entityType().trim().toUpperCase(Locale.ROOT) : "GENERIC");
        tpl.setTemplateHtml(cmd.templateHtml());
        tpl.setPageSize(cmd.pageSize() != null && !cmd.pageSize().isBlank() ? cmd.pageSize().trim().toUpperCase(Locale.ROOT) : "A4");
        ReportTemplate saved = templateRepository.saveAndFlush(tpl);
        return toView(saved);
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        ReportTemplate tpl = templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report template not found"));
        templateRepository.delete(tpl);
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID templateId, UUID entityId) {
        ReportTemplate tpl = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report template not found"));
        if (!tpl.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Report template is disabled");
        }
        Map<String, Object> model = buildEntityModel(tpl.getEntityType(), entityId);
        try {
            Template compiled = handlebars.compileInline(tpl.getTemplateHtml());
            Context ctx = Context.newBuilder(model)
                    .resolver(MapValueResolver.INSTANCE)
                    .build();
            String renderedBody = compiled.apply(ctx);
            String xhtml = wrapXhtmlIfNeeded(renderedBody, tpl.getPageSize());

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.useFastMode();
                builder.withHtmlContent(xhtml, null);
                builder.toStream(out);
                builder.run();
                return out.toByteArray();
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to render PDF report: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildEntityModel(String entityType, UUID entityId) {
        Map<String, Object> model = new HashMap<>();
        model.put("generatedAt", Instant.now().toString());
        if (entityId == null) {
            return model;
        }
        ReportDataProvider provider = dataProviders.get(entityType);
        if (provider != null) {
            model.putAll(provider.buildModel(entityId));
        } else {
            model.put("entityId", entityId.toString());
        }
        return model;
    }

    private String wrapXhtmlIfNeeded(String html, String pageSize) {
        String trimmed = html.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("<!doctype") || trimmed.toLowerCase(Locale.ROOT).startsWith("<html")) {
            return trimmed;
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                "<head><style>@page { size: " + pageSize + "; margin: 20mm; } body { font-family: sans-serif; font-size: 12px; color: #18181b; }</style></head>\n" +
                "<body>" + trimmed + "</body>\n" +
                "</html>";
    }

    private ReportTemplateView toView(ReportTemplate t) {
        return new ReportTemplateView(
                t.getId(),
                t.getName(),
                t.getEntityType(),
                t.getTemplateHtml(),
                t.getPageSize(),
                t.isEnabled(),
                t.getCreatedAt()
        );
    }
}
