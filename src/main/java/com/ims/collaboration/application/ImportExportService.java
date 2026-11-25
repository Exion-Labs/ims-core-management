package com.ims.collaboration.application;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ims.collaboration.spi.ImportModels;
import com.ims.collaboration.spi.ImportTargetHandler;
import com.ims.common.audit.AuditService;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.config.tenant.TenantContext;
import com.ims.shared.exception.BusinessRuleException;
import com.ims.shared.exception.ResourceNotFoundException;
import com.ims.shared.exception.ValidationException;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Generic streaming CSV (Jackson CSV) & XLSX (FastExcel) Import/Export service.
 * Uses a bounded TTL cache for import sessions and delegates target-specific validation/commits via {@link ImportTargetHandler}.
 */
@Service
@RequiresFeature(Feature.IMPORT_EXPORT)
public class ImportExportService {

    public static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    public static final int MAX_ROWS = 10_000;
    public static final double MAX_XLSX_COMPRESSION_RATIO = 100.0;
    public static final long MAX_XLSX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024; // 50 MB

    private record StoredImportSession(
            UUID sessionId,
            UUID tenantId,
            String targetType,
            String fileName,
            List<String> columns,
            List<Map<String, String>> rows,
            Instant createdAt
    ) {}

    private final Cache<UUID, StoredImportSession> sessions = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30))
            .maximumSize(500)
            .build();

    private final CsvMapper csvMapper;
    private final AuditService auditService;
    private final List<ImportTargetHandler> targetHandlers;

    public ImportExportService(AuditService auditService, List<ImportTargetHandler> targetHandlers) {
        this.auditService = auditService;
        this.targetHandlers = targetHandlers;
        this.csvMapper = new CsvMapper();
        this.csvMapper.enable(CsvParser.Feature.WRAP_AS_ARRAY);
    }

    public ImportModels.ImportSessionView createSession(String targetType, String fileName, byte[] fileBytes) {
        UUID tenantId = TenantContext.require();
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ValidationException("Uploaded import file is empty");
        }
        if (fileBytes.length > MAX_FILE_SIZE_BYTES) {
            throw new ValidationException("Import file exceeds maximum size of 10MB");
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
            throw new ValidationException("File contains no header row");
        }

        List<String> headers = rawTable.getFirst().stream()
                .map(h -> h == null ? "" : h.trim())
                .toList();

        List<Map<String, String>> dataRows = new ArrayList<>();
        for (int i = 1; i < rawTable.size(); i++) {
            if (dataRows.size() >= MAX_ROWS) {
                throw new ValidationException("Import file exceeds maximum limit of " + MAX_ROWS + " rows");
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

        return new ImportModels.ImportSessionView(
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

    public ImportModels.DryRunReport dryRun(
            UUID sessionId,
            Map<String, String> columnMapping,
            List<String> requiredFields
    ) {
        StoredImportSession session = requireTenantSession(sessionId);
        ImportTargetHandler handler = findHandler(session.targetType());

        List<ImportModels.RowValidationError> errors = new ArrayList<>();
        List<Map<String, String>> preview = new ArrayList<>();
        int validCount = 0;
        int invalidCount = 0;

        for (int i = 0; i < session.rows().size(); i++) {
            int rowNumber = i + 2;
            Map<String, String> rawRow = session.rows().get(i);
            Map<String, String> mappedRow = applyMapping(rawRow, columnMapping);
            if (preview.size() < 10) {
                preview.add(mappedRow);
            }

            List<ImportModels.RowValidationError> rowErrors = new ArrayList<>();
            for (String req : requiredFields) {
                String val = mappedRow.getOrDefault(req, "").trim();
                if (val.isEmpty()) {
                    rowErrors.add(new ImportModels.RowValidationError(rowNumber, req, "Required field '" + req + "' is missing or empty"));
                }
            }
            if (handler != null) {
                List<ImportModels.RowValidationError> custom = handler.validateRow(mappedRow);
                if (custom != null) {
                    for (ImportModels.RowValidationError err : custom) {
                        rowErrors.add(new ImportModels.RowValidationError(rowNumber, err.field(), err.message()));
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

        return new ImportModels.DryRunReport(
                session.sessionId(),
                session.targetType(),
                session.rows().size(),
                validCount,
                invalidCount,
                errors,
                preview
        );
    }

    public ImportModels.CommitResult commit(
            UUID sessionId,
            Map<String, String> columnMapping,
            List<String> requiredFields
    ) {
        StoredImportSession session = requireTenantSession(sessionId);
        ImportModels.DryRunReport dryRun = dryRun(sessionId, columnMapping, requiredFields);
        if (dryRun.invalidRows() > 0) {
            throw new BusinessRuleException("IMPORT_VALIDATION_FAILED",
                    "Cannot commit import session with " + dryRun.invalidRows() + " invalid rows");
        }

        ImportTargetHandler handler = findHandler(session.targetType());
        int committed = 0;
        for (Map<String, String> rawRow : session.rows()) {
            Map<String, String> mapped = applyMapping(rawRow, columnMapping);
            boolean didCommit = handler == null || handler.commitRow(mapped);
            if (didCommit) {
                committed++;
            }
        }
        sessions.invalidate(sessionId);
        auditService.record(session.targetType(), sessionId, "IMPORT",
                Map.of("fileName", session.fileName(), "committedRows", committed), null);
        return new ImportModels.CommitResult(sessionId, session.targetType(), committed);
    }

    private ImportTargetHandler findHandler(String targetType) {
        if (targetType == null) {
            return null;
        }
        return targetHandlers.stream()
                .filter(h -> h.supports(targetType))
                .findFirst()
                .orElse(null);
    }

    private StoredImportSession requireTenantSession(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        StoredImportSession session = sessions.getIfPresent(sessionId);
        if (session == null || !session.tenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("Import session not found");
        }
        return session;
    }

    private Map<String, String> applyMapping(Map<String, String> rawRow, Map<String, String> columnMapping) {
        Map<String, String> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            mapped.put(entry.getKey(), rawRow.getOrDefault(entry.getValue(), ""));
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
            throw new ValidationException("Invalid CSV file format: " + e.getMessage());
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
            throw new ValidationException("Invalid XLSX file format: " + e.getMessage());
        }
    }

    public static void verifyNoZipBomb(byte[] xlsxBytes) {
        long totalUncompressed = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xlsxBytes))) {
            while (zis.getNextEntry() != null) {
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    totalUncompressed += read;
                    if (totalUncompressed > MAX_XLSX_UNCOMPRESSED_BYTES) {
                        throw new ValidationException("XLSX file exceeds maximum uncompressed size limit (zip bomb guard)");
                    }
                    double ratio = (double) totalUncompressed / Math.max(1, xlsxBytes.length);
                    if (ratio > MAX_XLSX_COMPRESSION_RATIO) {
                        throw new ValidationException("XLSX compression ratio exceeds safety threshold (zip bomb guard)");
                    }
                }
                zis.closeEntry();
            }
        } catch (ValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new ValidationException("Invalid XLSX archive structure");
        }
    }
}
