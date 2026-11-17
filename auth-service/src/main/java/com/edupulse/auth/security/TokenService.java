package com.edupulse.auth.security;

import com.edupulse.auth.domain.UserAccount;
import com.edupulse.common.security.JwtClaimNames;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Issues RS256 access tokens carrying the user's tenant (organization, org unit, clearance) and roles. */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder encoder;
    private final RSAKey rsaKey;

    @Value("${edupulse.jwt.access-ttl:PT60M}")
    private Duration accessTtl;

    public record TokenResponse(String accessToken, long expiresIn, String tokenType) {
    }

    public TokenResponse issueFor(UserAccount user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtClaimNames.ISSUER)
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .claim(JwtClaimNames.EMAIL, user.email())
                .claim(JwtClaimNames.ORGANIZATION_ID, user.organizationId().toString())
                .claim(JwtClaimNames.ORGANIZATION_NAME, user.organizationName())
                .claim(JwtClaimNames.ORG_UNIT_ID, user.orgUnitId().toString())
                .claim(JwtClaimNames.CLEARANCE, user.clearanceLevel())
                .claim(JwtClaimNames.ROLES, List.copyOf(user.roles()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, accessTtl.toSeconds(), "Bearer");
    }
}
