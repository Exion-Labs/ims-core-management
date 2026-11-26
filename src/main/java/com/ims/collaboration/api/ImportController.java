package com.ims.collaboration.api;

import com.ims.collaboration.application.ImportExportService;
import com.ims.collaboration.spi.ImportModels;
import com.ims.common.idempotency.IdempotencyExempt;
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

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    public record DryRunRequest(
            @NotNull Map<String, String> columnMapping,
            List<String> requiredFields
    ) {}

    private final ImportExportService importExportService;

    public ImportController(ImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    @IdempotencyExempt(reason = "Multipart file upload cannot be fingerprinted or replayed as JSON")
    @PostMapping(value = "/sessions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportModels.ImportSessionView createSession(
            @RequestParam("targetType") @NotBlank String targetType,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        return importExportService.createSession(targetType, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/sessions/{sessionId}/dry-run")
    public ImportModels.DryRunReport dryRun(
            @PathVariable UUID sessionId,
            @Valid @RequestBody DryRunRequest request
    ) {
        return importExportService.dryRun(
                sessionId,
                request.columnMapping(),
                request.requiredFields() != null ? request.requiredFields() : List.of()
        );
    }

    @PostMapping("/sessions/{sessionId}/commit")
    public ImportModels.CommitResult commit(
            @PathVariable UUID sessionId,
            @Valid @RequestBody DryRunRequest request
    ) {
        return importExportService.commit(
                sessionId,
                request.columnMapping(),
                request.requiredFields() != null ? request.requiredFields() : List.of()
        );
    }
}
