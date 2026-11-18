package com.ims.common.io;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvGenerator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.config.tenant.TenantContext;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Generic streaming CSV (Jackson CSV) & XLSX (FastExcel) Import/Export framework.
 * Includes zip-bomb decompression ratio guard, max file size / row limits,
 * formula-injection cell escaping (=, +, -, @, \t, \r), column-mapping sessions, and dry-run validation.
 */
@Service
@RequiresFeature(Feature.IMPORT_EXPORT)
public class ImportExportService {

    public static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    public static final int MAX_ROWS = 10_000;
    public static final double MAX_XLSX_COMPRESSION_RATIO = 100.0;
    public static final long MAX_XLSX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024; // 50 MB

    public record RowValidationError(int rowNumber, String field, String message) {}

    public record ImportSessionView(
            UUID sessionId,
            UUID tenantId,
            String targetType,
            String fileName,
            List<String> detectedColumns,
            int totalRows,
            List<Map<String, String>> sampleRows,
            Instant createdAt
    ) {}

    public record DryRunReport(
            UUID sessionId,
            String targetType,
            int totalRows,
            int validRows,
            int invalidRows,
            List<RowValidationError> errors,
            List<Map<String, String>> previewMappedRows
    ) {}

    public record CommitResult(
            UUID sessionId,
            String targetType,
            int committedRows
    ) {}

    private record StoredImportSession(
            UUID sessionId,
            UUID tenantId,
            String targetType,
            String fileName,
            List<String> columns,
            List<Map<String, String>> rows,
            Instant createdAt
    ) {}

    private final Map<UUID, StoredImportSession> sessions = new ConcurrentHashMap<>();
    private final CsvMapper csvMapper;
    private final AuditService auditService;

    public ImportExportService(AuditService auditService) {
        this.auditService = auditService;
        this.csvMapper = new CsvMapper();
        this.csvMapper.enable(CsvParser.Feature.WRAP_AS_ARRAY);
        this.csvMapper.enable(CsvGenerator.Feature.ALWAYS_QUOTE_STRINGS);
    }

    /**
     * Escapes values that start with formula characters (=, +, -, @, tab, CR) to prevent CSV/Excel formula injection.
     */
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

    /**
     * Parses a CSV or XLSX payload into a tenant-scoped ImportSession for column mapping and dry-run validation.
     */
    public ImportSessionView createSession(String targetType, String fileName, byte[] fileBytes) {
        UUID tenantId = TenantContext.require();
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded import file is empty");
        }
        if (fileBytes.length > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import file exceeds maximum size of 10MB");
        }

        String lowerName = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "import.csv";
        List<List<String>> rawTable;
        if (lowerName.endsWith(".xlsx")) {
            verifyNoZipBomb(fileBytes);
            rawTable = parseXlsx(fileBytes);
        } else {
            rawTable = parseCsv(fileBytes);
        }

        if (rawTable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File contains no header row");
        }

        List<String> headers = rawTable.getFirst().stream()
                .map(h -> h == null ? "" : h.trim())
                .toList();

        List<Map<String, String>> dataRows = new ArrayList<>();
        for (int i = 1; i < rawTable.size(); i++) {
            if (dataRows.size() >= MAX_ROWS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import file exceeds maximum limit of " + MAX_ROWS + " rows");
            }
            List<String> cells = rawTable.get(i);
            Map<String, String> rowMap = new LinkedHashMap<>();
            boolean anyNonBlank = false;
            for (int colIdx = 0; colIdx < headers.size(); colIdx++) {
                String colName = headers.get(colIdx);
                if (colName.isEmpty()) {
                    continue;
                }
                String cellValue = colIdx < cells.size() && cells.get(colIdx) != null ? cells.get(colIdx).trim() : "";
                if (!cellValue.isEmpty()) {
                    anyNonBlank = true;
                }
                rowMap.put(colName, cellValue);
            }
            if (anyNonBlank) {
                dataRows.add(rowMap);
            }
        }

        UUID sessionId = UUID.randomUUID();
        StoredImportSession session = new StoredImportSession(
                sessionId,
                tenantId,
                targetType,
                fileName,
                headers,
                dataRows,
                Instant.now()
        );
        sessions.put(sessionId, session);

        return new ImportSessionView(
                sessionId,
                tenantId,
                targetType,
                fileName,
                headers,
                dataRows.size(),
                dataRows.stream().limit(5).toList(),
                session.createdAt()
        );
    }

    /**
     * Runs dry-run validation on a session using the caller's column mapping (targetField -> sourceColumn)
     * and custom row validator.
     */
    public DryRunReport dryRun(
            UUID sessionId,
            Map<String, String> columnMapping,
            List<String> requiredFields,
            Function<Map<String, String>, List<RowValidationError>> customValidator
    ) {
        StoredImportSession session = requireTenantSession(sessionId);
        List<RowValidationError> errors = new ArrayList<>();
        List<Map<String, String>> preview = new ArrayList<>();
        int validCount = 0;
        int invalidCount = 0;

        for (int i = 0; i < session.rows().size(); i++) {
            int rowNumber = i + 2; // 1-based + header row
            Map<String, String> rawRow = session.rows().get(i);
            Map<String, String> mappedRow = applyMapping(rawRow, columnMapping);
            if (preview.size() < 10) {
                preview.add(mappedRow);
            }

            List<RowValidationError> rowErrors = new ArrayList<>();
            for (String req : requiredFields) {
                String val = mappedRow.getOrDefault(req, "").trim();
                if (val.isEmpty()) {
                    rowErrors.add(new RowValidationError(rowNumber, req, "Required field '" + req + "' is missing or empty"));
                }
            }
            if (customValidator != null) {
                List<RowValidationError> custom = customValidator.apply(mappedRow);
                if (custom != null) {
                    for (RowValidationError err : custom) {
                        rowErrors.add(new RowValidationError(rowNumber, err.field(), err.message()));
                    }
                }
            }

            if (rowErrors.isEmpty()) {
                validCount++;
            } else {
                invalidCount++;
                errors.addAll(rowErrors);
            }
        }

        return new DryRunReport(
                session.sessionId(),
                session.targetType(),
                session.rows().size(),
                validCount,
                invalidCount,
                errors,
                preview
        );
    }

    /**
     * Commits a validated session by invoking the consumer for every valid mapped row and records an IMPORT audit event.
     */
    public CommitResult commit(
            UUID sessionId,
            Map<String, String> columnMapping,
            List<String> requiredFields,
            Function<Map<String, String>, Boolean> rowCommitter
    ) {
        StoredImportSession session = requireTenantSession(sessionId);
        DryRunReport dryRun = dryRun(sessionId, columnMapping, requiredFields, null);
        if (dryRun.invalidRows() > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot commit import session with " + dryRun.invalidRows() + " invalid rows");
        }

        int committed = 0;
        for (Map<String, String> rawRow : session.rows()) {
            Map<String, String> mapped = applyMapping(rawRow, columnMapping);
            if (Boolean.TRUE.equals(rowCommitter.apply(mapped))) {
                committed++;
            }
        }
        sessions.remove(sessionId);
        auditService.record(session.targetType(), sessionId, "IMPORT",
                Map.of("fileName", session.fileName(), "committedRows", committed), null);
        return new CommitResult(sessionId, session.targetType(), committed);
    }

    public byte[] exportCsv(List<String> headers, List<List<String>> rows, String entityType) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CsvSchema.Builder builder = CsvSchema.builder();
            for (String h : headers) {
                builder.addColumn(escapeFormulaCell(h));
            }
            CsvSchema schema = builder.build().withHeader();
            var writer = csvMapper.writer(schema).writeValues(out);
            for (List<String> row : rows) {
                List<String> escaped = row.stream().map(ImportExportService::escapeFormulaCell).toList();
                writer.write(escaped);
            }
            writer.close();
            auditService.record(entityType, null, "EXPORT", Map.of("format", "CSV", "rows", rows.size()), null);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to export CSV", e);
        }
    }

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

    public String getSessionTargetType(UUID sessionId) {
        return requireTenantSession(sessionId).targetType();
    }

    private StoredImportSession requireTenantSession(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        StoredImportSession session = sessions.get(sessionId);
        if (session == null || !session.tenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import session not found");
        }
        return session;
    }

    private Map<String, String> applyMapping(Map<String, String> rawRow, Map<String, String> columnMapping) {
        Map<String, String> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            String targetField = entry.getKey();
            String sourceCol = entry.getValue();
            mapped.put(targetField, rawRow.getOrDefault(sourceCol, ""));
        }
        return mapped;
    }

    private List<List<String>> parseCsv(byte[] bytes) {
        try (MappingIterator<List<String>> it = csvMapper.readerForListOf(String.class)
                .with(CsvSchema.emptySchema())
                .readValues(new String(bytes, StandardCharsets.UTF_8))) {
            List<List<String>> result = new ArrayList<>();
            while (it.hasNext()) {
                result.add(it.next());
            }
            return result;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid CSV file format: " + e.getMessage());
        }
    }

    private List<List<String>> parseXlsx(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes);
             ReadableWorkbook wb = new ReadableWorkbook(in)) {
            Sheet sheet = wb.getFirstSheet();
            List<List<String>> result = new ArrayList<>();
            try (Stream<Row> rows = sheet.openStream()) {
                rows.forEach(r -> {
                    List<String> rowCells = new ArrayList<>();
                    for (int i = 0; i < r.getCellCount(); i++) {
                        rowCells.add(r.getCellText(i));
                    }
                    result.add(rowCells);
                });
            }
            return result;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid XLSX file format: " + e.getMessage());
        }
    }

    /**
     * Guards against XLSX zip-bomb decompression attacks by inspecting entry expansion ratios before parsing.
     */
    public static void verifyNoZipBomb(byte[] xlsxBytes) {
        long totalUncompressed = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xlsxBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                long entryBytes = 0;
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    entryBytes += read;
                    totalUncompressed += read;
                    if (totalUncompressed > MAX_XLSX_UNCOMPRESSED_BYTES) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX file exceeds maximum uncompressed size limit (zip bomb guard)");
                    }
                    double ratio = (double) totalUncompressed / Math.max(1, xlsxBytes.length);
                    if (ratio > MAX_XLSX_COMPRESSION_RATIO) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "XLSX compression ratio exceeds safety threshold (zip bomb guard)");
                    }
                }
                zis.closeEntry();
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid XLSX archive structure");
        }
    }
}
