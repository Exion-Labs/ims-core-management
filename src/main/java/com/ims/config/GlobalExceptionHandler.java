package com.ims.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.ims.common.idempotency.IdempotencyAspect;
import com.ims.common.idempotency.IdempotentReplay;
import com.ims.entitlement.spi.FeatureNotEnabledException;
import com.ims.entitlement.spi.LimitReachedException;
import com.ims.shared.exception.BusinessRuleException;
import com.ims.shared.exception.ConflictException;
import com.ims.shared.exception.ForbiddenException;
import com.ims.shared.exception.ResourceNotFoundException;
import com.ims.shared.exception.UnauthorizedException;
import com.ims.shared.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IdempotentReplay.class)
    public ResponseEntity<JsonNode> handleIdempotentReplay(IdempotentReplay replay) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(replay.getStatus())
                .header(IdempotencyAspect.REPLAYED_HEADER, "true");
        if (replay.getBody() == null) {
            return builder.build();
        }
        return builder.contentType(MediaType.APPLICATION_JSON).body(replay.getBody());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return errorBody(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        return errorBody(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessRule(BusinessRuleException ex) {
        return errorBody(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(ValidationException ex) {
        return errorBody(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        return errorBody(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        return errorBody(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(FeatureNotEnabledException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureNotEnabled(FeatureNotEnabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "FEATURE_NOT_ENABLED",
                "feature", ex.getFeature().name(),
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(LimitReachedException.class)
    public ResponseEntity<Map<String, Object>> handleLimitReached(LimitReachedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "LIMIT_REACHED",
                "feature", ex.getFeature().name(),
                "limit", ex.getLimit(),
                "current", ex.getCurrent(),
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return errorBody(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return errorBody(status, status.name(), ex.getReason() != null ? ex.getReason() : status.getReasonPhrase());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "Request validation failed",
                "fields", fieldErrors,
                "timestamp", Instant.now().toString()
        ));
    }

    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message != null ? message : status.getReasonPhrase(),
                "timestamp", Instant.now().toString()
        ));
    }
}
