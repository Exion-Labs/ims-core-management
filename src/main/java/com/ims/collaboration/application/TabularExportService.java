package com.ims.collaboration.application;

import com.fasterxml.jackson.dataformat.csv.CsvGenerator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.ims.collaboration.spi.TabularExportPort;
import com.ims.common.audit.AuditService;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Exports tabular data to CSV and XLSX with formula-injection protection.
 */
@Service
@RequiresFeature(Feature.IMPORT_EXPORT)
public class TabularExportService implements TabularExportPort {

    private final CsvMapper csvMapper;
    private final AuditService auditService;

    public TabularExportService(AuditService auditService) {
        this.auditService = auditService;
        this.csvMapper = new CsvMapper();
        this.csvMapper.enable(CsvGenerator.Feature.ALWAYS_QUOTE_STRINGS);
    }

    public static String escapeFormulaCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    @Override
    public byte[] exportCsv(List<String> headers, List<List<String>> rows, String entityType) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CsvSchema.Builder builder = CsvSchema.builder();
            for (String h : headers) {
                builder.addColumn(escapeFormulaCell(h));
            }
            CsvSchema schema = builder.build().withHeader();
            var writer = csvMapper.writer(schema).writeValues(out);
            for (List<String> row : rows) {
                List<String> escaped = row.stream().map(TabularExportService::escapeFormulaCell).toList();
                writer.write(escaped);
            }
            writer.close();
            auditService.record(entityType, null, "EXPORT", Map.of("format", "CSV", "rows", rows.size()), null);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to export CSV", e);
        }
    }

    @Override
    public byte[] exportXlsx(String sheetName, List<String> headers, List<List<String>> rows, String entityType) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             Workbook wb = new Workbook(out, "IMS", "1.0")) {
            Worksheet ws = wb.newWorksheet(sheetName != null ? sheetName : "Export");
            for (int c = 0; c < headers.size(); c++) {
                ws.value(0, c, escapeFormulaCell(headers.get(c)));
            }
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    ws.value(r + 1, c, escapeFormulaCell(row.get(c)));
                }
            }
            wb.finish();
            auditService.record(entityType, null, "EXPORT", Map.of("format", "XLSX", "rows", rows.size()), null);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to export XLSX", e);
        }
    }
}
