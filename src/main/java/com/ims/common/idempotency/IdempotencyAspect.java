package com.ims.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ims.config.security.TenantUserPrincipal;
import com.ims.shared.exception.BusinessRuleException;
import com.ims.shared.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default-on idempotency for every tenant POST/PATCH controller handler (opt out with
 * {@link IdempotencyExempt}).
 *
 * The handler runs inside a transaction together with the insert of the {@link IdempotencyRecord},
 * so the record, the business change and any outbox events commit atomically. Concurrent duplicates
 * block on the unique index until the first request commits, then replay its stored response.
 * Failed requests roll back (including the record) so the client may retry with the same key.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 50)
public class IdempotencyAspect {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern VALID_KEY = Pattern.compile("^[\\x21-\\x7E]+$");

    private final IdempotencyRecordRepository repository;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public IdempotencyAspect(
            IdempotencyRecordRepository repository,
            IdempotencyProperties properties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** Wraps a failure of the business handler so it passes through the transaction template untouched. */
    private static final class HandlerFailure extends RuntimeException {
        HandlerFailure(Throwable cause) {
            super(cause);
        }
    }

    /** Signals that a fresh record for this key already exists (committed by an earlier request). */
    private static final class ExistingRecord extends RuntimeException {
        private final transient IdempotencyRecord record;

        ExistingRecord(IdempotencyRecord record) {
            super(null, null, false, false);
            this.record = record;
        }
    }

    @Around("@within(org.springframework.web.bind.annotation.RestController)"
            + " && (@annotation(org.springframework.web.bind.annotation.PostMapping)"
            + " || @annotation(org.springframework.web.bind.annotation.PatchMapping))"
            + " && !@annotation(com.ims.common.idempotency.IdempotencyExempt)"
            + " && !@within(com.ims.common.idempotency.IdempotencyExempt)"
            + " && !within(com.ims.platform..*)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }
        HttpServletRequest request = currentRequest();
        TenantUserPrincipal principal = currentPrincipal();
        if (request == null || principal == null) {
            return pjp.proceed();
        }

        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            boolean required = principal.apiTokenAuth()
                    ? properties.isRequireForApiTokens()
                    : properties.isRequireForSessions();
            if (required) {
                throw new ValidationException("IDEMPOTENCY_KEY_REQUIRED",
                        "The " + HEADER + " header is required for this request.");
            }
            return pjp.proceed();
        }
        key = key.trim();
        if (key.length() > properties.getMaxKeyLength() || !VALID_KEY.matcher(key).matches()) {
            throw new ValidationException("IDEMPOTENCY_KEY_INVALID",
                    "The " + HEADER + " header must be 1-" + properties.getMaxKeyLength()
                            + " printable ASCII characters without spaces.");
        }

        UUID principalId = principal.apiTokenAuth() ? principal.apiTokenId() : principal.userId();
        String requestHash = fingerprint(request, pjp);
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        final String idemKey = key;

        try {
            return writeTx.execute(status -> {
                Optional<IdempotencyRecord> existing = repository.findByPrincipalIdAndIdemKey(principalId, idemKey);
                if (existing.isPresent()) {
                    if (!isExpired(existing.get())) {
                        throw new ExistingRecord(existing.get());
                    }
                    repository.delete(existing.get());
                    repository.flush();
                }

                // Take the unique-index lock before running the handler.
                IdempotencyRecord record = repository.saveAndFlush(
                        new IdempotencyRecord(principalId, idemKey, requestHash));

                Object result;
                try {
                    result = pjp.proceed();
                } catch (Throwable t) {
                    throw new HandlerFailure(t);
                }

                int statusCode;
                Object body;
                if (result instanceof ResponseEntity<?> entity) {
                    statusCode = entity.getStatusCode().value();
                    body = entity.getBody();
                } else {
                    statusCode = defaultStatus(method);
                    body = result;
                }
                JsonNode bodyNode = body == null ? null : objectMapper.valueToTree(body);
                record.complete(statusCode, bodyNode);
                repository.save(record);
                return result;
            });
        } catch (HandlerFailure failure) {
            throw failure.getCause();
        } catch (ExistingRecord existing) {
            throw resolve(existing.record, requestHash);
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same key committed first (our insert blocked, then failed).
            Optional<IdempotencyRecord> winner = readTx.execute(
                    s -> repository.findByPrincipalIdAndIdemKey(principalId, idemKey));
            if (winner != null && winner.isPresent() && !isExpired(winner.get())) {
                throw resolve(winner.get(), requestHash);
            }
            throw race;
        }
    }

    private RuntimeException resolve(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            return new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                    "This " + HEADER + " was already used with a different request.");
        }
        return new IdempotentReplay(
                record.getResponseStatus() != null ? record.getResponseStatus() : 200,
                record.getResponseBody());
    }

    private boolean isExpired(IdempotencyRecord record) {
        return record.getCreatedAt().plus(properties.getTtl()).isBefore(Instant.now());
    }

    private static int defaultStatus(Method method) {
        ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);
        return rs != null ? rs.code().value() : 200;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private static TenantUserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof TenantUserPrincipal p ? p : null;
    }

    /** SHA-256 over method, URI, query and the deserialized handler arguments (never the raw body). */
    private String fingerprint(HttpServletRequest request, ProceedingJoinPoint pjp) throws Exception {
        StringBuilder sb = new StringBuilder()
                .append(request.getMethod()).append('\n')
                .append(request.getRequestURI()).append('\n')
                .append(request.getQueryString() == null ? "" : request.getQueryString()).append('\n');
        for (Object arg : pjp.getArgs()) {
            if (arg == null) {
                sb.append("null\n");
                continue;
            }
            if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                    || arg instanceof Principal || arg instanceof Authentication
                    || arg instanceof TenantUserPrincipal || arg instanceof Pageable
                    || arg instanceof MultipartFile || arg instanceof InputStream) {
                continue;
            }
            sb.append(canonicalMapper.writeValueAsString(arg)).append('\n');
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
