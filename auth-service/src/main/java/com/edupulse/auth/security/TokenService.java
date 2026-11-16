package com.edupulse.auth.security;

import com.edupulse.auth.domain.UserAccount;
import com.edupulse.common.security.JwtClaimNames;
import com.edupulse.common.security.Roles;
import com.edupulse.common.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Issues RS256 access tokens (15 min), rotating opaque refresh tokens (30 min) and service tokens. */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder encoder;
    private final SigningKeyService keys;
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    @Value("${edupulse.jwt.access-ttl:PT15M}")
    private Duration accessTtl;

    @Value("${edupulse.jwt.refresh-ttl:PT30M}")
    private Duration refreshTtl;

    @Value("${edupulse.jwt.service-ttl:PT10M}")
    private Duration serviceTtl;

    public record TokenPair(String accessToken, long expiresIn, String refreshToken, String tokenType) {
    }

    public TokenPair issueFor(UserAccount user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtClaimNames.ISSUER)
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .id(UUID.randomUUID().toString())
                .claim(JwtClaimNames.EMAIL, user.email())
                .claim(JwtClaimNames.ORGANIZATION_ID, user.organizationId().toString())
                .claim(JwtClaimNames.ORGANIZATION_NAME, user.organizationName())
                .claim(JwtClaimNames.ORG_UNIT_ID, user.orgUnitId().toString())
                .claim(JwtClaimNames.CLEARANCE, user.clearanceLevel())
                .claim(JwtClaimNames.ROLES, List.copyOf(user.roles()))
                .build();
        String access = encode(claims);
        String refresh = newRefreshToken(user.id(), now);
        return new TokenPair(access, accessTtl.toSeconds(), refresh, "Bearer");
    }

    public TokenPair issueServiceToken(String clientId, String scopes) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtClaimNames.ISSUER)
                .subject("svc:" + clientId)
                .issuedAt(now)
                .expiresAt(now.plus(serviceTtl))
                .id(UUID.randomUUID().toString())
                .claim(JwtClaimNames.SCOPE, scopes)
                .claim(JwtClaimNames.ROLES, List.of(Roles.SERVICE))
                .build();
        return new TokenPair(encode(claims), serviceTtl.toSeconds(), null, "Bearer");
    }

    /** Validates and rotates a refresh token; returns the owning user id. */
    @Transactional
    public UUID consumeRefreshToken(String refreshToken) {
        String hash = sha256(refreshToken);
        Optional<UUID> userId = jdbc.sql("""
                        update auth.refresh_token set revoked = true
                        where token_hash = ? and not revoked and expires_at > now()
                        returning user_id
                        """)
                .param(hash).query(UUID.class).optional();
        return userId.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token"));
    }

    private String newRefreshToken(UUID userId, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("insert into auth.refresh_token (token_hash, user_id, expires_at) values (?, ?, ?)")
                .params(sha256(token), userId, java.sql.Timestamp.from(now.plus(refreshTtl)))
                .update();
        return token;
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.rsaKey().getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
