package com.ims.collaboration.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ImportModels {

    private ImportModels() {}

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
}
