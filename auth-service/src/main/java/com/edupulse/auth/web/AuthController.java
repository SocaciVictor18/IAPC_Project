package com.edupulse.auth.web;

import com.edupulse.auth.domain.UserAccount;
import com.edupulse.auth.repository.UserRepository;
import com.edupulse.auth.security.SigningKeyService;
import com.edupulse.auth.security.TokenService;
import com.edupulse.auth.security.TokenService.TokenPair;
import com.edupulse.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    /** Hash of a random string: compared against when the e-mail is unknown, to keep timing uniform. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BaZz0Vi6pCiLgtzJN0PQpN4q6Dle";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final SigningKeyService keys;
    private final JdbcClient jdbc;

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record ServiceTokenRequest(@NotBlank String clientId, @NotBlank String clientSecret) {
    }

    @PostMapping("/api/auth/login")
    public TokenPair login(@Valid @RequestBody LoginRequest req) {
        UserAccount user = users.findByEmail(req.email()).orElse(null);
        boolean ok = passwordEncoder.matches(req.password(), user == null ? DUMMY_HASH : user.passwordHash());
        if (user == null || !ok || !user.enabled()) {
            log.info("Failed login for {}", req.email());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return tokens.issueFor(user);
    }

    @PostMapping("/api/auth/refresh")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest req) {
        UUID userId = tokens.consumeRefreshToken(req.refreshToken());
        UserAccount user = users.findById(userId)
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User disabled"));
        return tokens.issueFor(user);
    }

    /** Client-credentials flow for service-to-service calls (e.g. compliance -> ingestion /internal). */
    @PostMapping("/api/auth/service-token")
    public TokenPair serviceToken(@Valid @RequestBody ServiceTokenRequest req) {
        Map<String, Object> client = jdbc.sql("select secret_hash, scopes from auth.service_client where client_id = ?")
                .param(req.clientId()).query().listOfRows().stream().findFirst().orElse(null);
        String hash = client == null ? DUMMY_HASH : (String) client.get("secret_hash");
        if (!passwordEncoder.matches(req.clientSecret(), hash) || client == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid client credentials");
        }
        return tokens.issueServiceToken(req.clientId(), (String) client.get("scopes"));
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.publicJwks();
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        UserAccount u = users.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> ApiException.notFound("User"));
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", u.id());
        me.put("email", u.email());
        me.put("fullName", u.fullName());
        me.put("organizationId", u.organizationId());
        me.put("organizationName", u.organizationName());
        me.put("orgUnitId", u.orgUnitId());
        me.put("orgUnitCode", u.orgUnitCode());
        me.put("orgUnitName", u.orgUnitName());
        me.put("clearanceLevel", u.clearanceLevel());
        me.put("roles", u.roles());
        me.put("tokenExpiresAt", jwt.getExpiresAt());
        return me;
    }
}
