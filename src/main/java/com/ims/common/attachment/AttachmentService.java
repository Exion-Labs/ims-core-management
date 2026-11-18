package com.ims.common.attachment;

import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.config.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manages attachments via S3-compatible presigned PUT and GET URLs so files never traverse the Next.js BFF proxy.
 */
@Service
public class AttachmentService {

    public record UploadUrlRequest(
            String entityType,
            UUID entityId,
            String fileName,
            String contentType,
            long sizeBytes,
            String comment
    ) {}

    public record UploadUrlResponse(
            UUID attachmentId,
            String objectKey,
            String uploadUrl,
            Instant expiresAt
    ) {}

    public record DownloadUrlResponse(
            UUID attachmentId,
            String fileName,
            String contentType,
            long sizeBytes,
            String downloadUrl,
            Instant expiresAt
    ) {}

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB

    private final AttachmentRepository attachmentRepository;
    private final EntitlementService entitlementService;
    private final String bucketName;
    private final S3Presigner presigner;

    public AttachmentService(
            AttachmentRepository attachmentRepository,
            EntitlementService entitlementService,
            @Value("${ims.storage.endpoint:http://localhost:3900}") String endpoint,
            @Value("${ims.storage.region:us-east-1}") String region,
            @Value("${ims.storage.bucket:ims-dev-files}") String bucketName,
            @Value("${ims.storage.access-key:GKdevAccessKey0000000000}") String accessKey,
            @Value("${ims.storage.secret-key:devSecretKey00000000000000000000000000000000}") String secretKey
    ) {
        this.attachmentRepository = attachmentRepository;
        this.entitlementService = entitlementService;
        this.bucketName = bucketName;
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Transactional
    public UploadUrlResponse requestUploadUrl(UploadUrlRequest req) {
        if (req.sizeBytes() <= 0 || req.sizeBytes() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File size must be between 1B and 50MB");
        }
        UUID tenantId = TenantContext.require();

        long currentBytes = attachmentRepository.sumReadySizeBytes();
        long currentMb = (currentBytes + req.sizeBytes()) / (1024 * 1024);
        entitlementService.requireWithinLimit(Feature.STORAGE_MB, currentMb);

        String sanitizedFileName = req.fileName().replaceAll("[^a-zA-Z0-9._-]", "_");
        Attachment attachment = new Attachment();
        attachment.setEntityType(req.entityType());
        attachment.setEntityId(req.entityId());
        attachment.setFileName(sanitizedFileName);
        attachment.setContentType(req.contentType());
        attachment.setSizeBytes(req.sizeBytes());
        attachment.setStatus(Attachment.Status.PENDING.name());
        attachment.setComment(req.comment());
        String objectKey = tenantId + "/" + attachment.getId() + "/" + sanitizedFileName;
        attachment.setObjectKey(objectKey);
        attachment = attachmentRepository.save(attachment);

        Duration ttl = Duration.ofMinutes(5);
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(req.contentType())
                .contentLength(req.sizeBytes())
                .build();

        String presignedUrl = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putObjectRequest)
                .build()).url().toString();

        return new UploadUrlResponse(attachment.getId(), objectKey, presignedUrl, Instant.now().plus(ttl));
    }

    @Transactional
    public Attachment completeUpload(UUID attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        attachment.setStatus(Attachment.Status.READY.name());
        return attachmentRepository.save(attachment);
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse getDownloadUrl(UUID attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        if (!Attachment.Status.READY.name().equals(attachment.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attachment upload is not yet completed");
        }
        Duration ttl = Duration.ofMinutes(5);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(attachment.getObjectKey())
                .responseContentType(attachment.getContentType())
                .build();

        String presignedUrl = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getObjectRequest)
                .build()).url().toString();

        return new DownloadUrlResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                presignedUrl,
                Instant.now().plus(ttl)
        );
    }

    @Transactional(readOnly = true)
    public List<Attachment> listForEntity(String entityType, UUID entityId) {
        return attachmentRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    @Transactional
    public void deleteAttachment(UUID attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
        attachmentRepository.delete(attachment);
    }
}
