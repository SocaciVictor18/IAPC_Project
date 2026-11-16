package com.edupulse.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Maps the {@code roles} claim to {@code ROLE_*} authorities and the {@code scope} claim to
 * {@code SCOPE_*} authorities, so {@code @PreAuthorize("hasRole('ADMIN')")} and
 * {@code hasAuthority('SCOPE_internal')} work on every service.
 */
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        List<String> roles = jwt.getClaimAsStringList(JwtClaimNames.ROLES);
        if (roles != null) {
            for (String role : roles) {
                authorities.add(new SimpleGrantedAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role));
            }
        }
        String scope = jwt.getClaimAsString(JwtClaimNames.SCOPE);
        if (scope != null) {
            for (String s : scope.split("\\s+")) {
                if (!s.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
                }
            }
        }
        return authorities;
    }
}
