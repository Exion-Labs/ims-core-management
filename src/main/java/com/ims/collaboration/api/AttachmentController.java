package com.ims.collaboration.api;

import com.ims.collaboration.application.AttachmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    public record CreateUploadUrlPayload(
            @NotBlank String entityType,
            @NotNull UUID entityId,
            @NotBlank String fileName,
            @NotBlank String contentType,
            @Positive long sizeBytes,
            String comment
    ) {}

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping("/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentService.UploadUrlResponse requestUploadUrl(@Valid @RequestBody CreateUploadUrlPayload payload) {
        return attachmentService.requestUploadUrl(new AttachmentService.UploadUrlRequest(
                payload.entityType(),
                payload.entityId(),
                payload.fileName(),
                payload.contentType(),
                payload.sizeBytes(),
                payload.comment()
        ));
    }

    @PostMapping("/{id}/complete")
    public AttachmentService.AttachmentView completeUpload(@PathVariable UUID id) {
        return attachmentService.completeUpload(id);
    }

    @GetMapping("/{id}/download-url")
    public AttachmentService.DownloadUrlResponse getDownloadUrl(@PathVariable UUID id) {
        return attachmentService.getDownloadUrl(id);
    }

    @GetMapping
    public List<AttachmentService.AttachmentView> listForEntity(@RequestParam String entityType, @RequestParam UUID entityId) {
        return attachmentService.listForEntity(entityType, entityId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ADMIN:DELETE') or hasAuthority('PART:DELETE') or hasAuthority('STOCK:DELETE')")
    public void delete(@PathVariable UUID id) {
        attachmentService.deleteAttachment(id);
    }
}
