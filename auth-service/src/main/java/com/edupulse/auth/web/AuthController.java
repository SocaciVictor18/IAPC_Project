package com.edupulse.auth.web;

import com.edupulse.auth.domain.UserAccount;
import com.edupulse.auth.repository.UserRepository;
import com.edupulse.auth.security.TokenService;
import com.edupulse.auth.security.TokenService.TokenResponse;
import com.edupulse.common.web.ApiException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final RSAKey rsaKey;

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /** Public: e-mail + password → signed JWT. */
    @PostMapping("/api/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        UserAccount user = users.findByEmail(req.email())
                .filter(UserAccount::enabled)
                .filter(u -> passwordEncoder.matches(req.password(), u.passwordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return tokens.issueFor(user);
    }

    /** Public key(s) used by the gateway and the services to verify JWT signatures. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

    /** Profile of the logged-in user. */
    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        UserAccount u = users.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> ApiException.notFound("User"));
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", u.id());
        me.put("email", u.email());
        me.put("fullName", u.fullName());
        me.put("organizationName", u.organizationName());
        me.put("orgUnitCode", u.orgUnitCode());
        me.put("orgUnitName", u.orgUnitName());
        me.put("clearanceLevel", u.clearanceLevel());
        me.put("roles", u.roles());
        return me;
    }
}
