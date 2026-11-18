package com.ims.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies short-lived session JWTs (~10 min) with aud=ims-tenant and tid=tenantId,
 * plus secure random token generation and SHA-256 hashing for refresh tokens and API tokens.
 */
@Service
public class JwtTokenService {

    public static final String AUDIENCE_TENANT = "ims-tenant";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKey tenantSigningKey;
    private final Duration accessTokenTtl;

    public JwtTokenService(
            @Value("${ims.security.jwt-secret:ims-dev-tenant-jwt-secret-key-must-be-at-least-32-bytes-long-2026}") String jwtSecret,
            @Value("${ims.security.access-token-minutes:10}") long accessTokenMinutes
    ) {
        this.tenantSigningKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(accessTokenMinutes);
    }

    public String createTenantAccessToken(
            UUID userId,
            UUID tenantId,
            String email,
            String fullName,
            Set<String> permissions
    ) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenTtl);
        return Jwts.builder()
                .subject(userId.toString())
                .audience().add(AUDIENCE_TENANT).and()
                .claim("tid", tenantId.toString())
                .claim("email", email)
                .claim("name", fullName)
                .claim("perms", List.copyOf(permissions))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(tenantSigningKey)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public TenantUserPrincipal parseTenantAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(tenantSigningKey)
                .requireAudience(AUDIENCE_TENANT)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        UUID userId = UUID.fromString(claims.getSubject());
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        String email = claims.get("email", String.class);
        String name = claims.get("name", String.class);
        List<String> permsList = claims.get("perms", List.class);
        Set<String> perms = permsList != null ? Set.copyOf(permsList) : Set.of();

        return new TenantUserPrincipal(userId, tenantId, email, name, perms, false, null);
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }

    public static String generateRandomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
