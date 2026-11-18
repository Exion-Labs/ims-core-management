package com.ims.common.io;

import com.ims.part.PartService;
import com.ims.stock.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    public record DryRunRequest(
            @NotNull Map<String, String> columnMapping,
            List<String> requiredFields
    ) {}

    private final ImportExportService importExportService;
    private final PartService partService;
    private final StockService stockService;

    public ImportController(ImportExportService importExportService, PartService partService, StockService stockService) {
        this.importExportService = importExportService;
        this.partService = partService;
        this.stockService = stockService;
    }

    @PostMapping(value = "/sessions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportExportService.ImportSessionView createSession(
            @RequestParam("targetType") @NotBlank String targetType,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        return importExportService.createSession(targetType, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/sessions/{sessionId}/dry-run")
    public ImportExportService.DryRunReport dryRun(
            @PathVariable UUID sessionId,
            @Valid @RequestBody DryRunRequest request
    ) {
        String targetType = importExportService.getSessionTargetType(sessionId);
        Function<Map<String, String>, List<ImportExportService.RowValidationError>> customValidator = null;
        if ("PART".equalsIgnoreCase(targetType) || "PARTS".equalsIgnoreCase(targetType)) {
            customValidator = partService::validatePartImportRow;
        }
        return importExportService.dryRun(
                sessionId,
                request.columnMapping(),
                request.requiredFields() != null ? request.requiredFields() : List.of(),
                customValidator
        );
    }

    @PostMapping("/sessions/{sessionId}/commit")
    public ImportExportService.CommitResult commit(
            @PathVariable UUID sessionId,
            @Valid @RequestBody DryRunRequest request
    ) {
        String targetType = importExportService.getSessionTargetType(sessionId);
        Function<Map<String, String>, Boolean> rowCommitter = row -> true;
        if ("PART".equalsIgnoreCase(targetType) || "PARTS".equalsIgnoreCase(targetType) || "PartCatalogPreview".equalsIgnoreCase(targetType)) {
            rowCommitter = partService::commitImportedPartRow;
        } else if ("PART_PARAMETER".equalsIgnoreCase(targetType)) {
            rowCommitter = partService::commitImportedPartParameterRow;
        } else if ("STOCK".equalsIgnoreCase(targetType) || "STOCK_ITEM".equalsIgnoreCase(targetType)) {
            rowCommitter = stockService::commitImportedStockRow;
        }
        return importExportService.commit(
                sessionId,
                request.columnMapping(),
                request.requiredFields() != null ? request.requiredFields() : List.of(),
                rowCommitter
        );
    }
}
