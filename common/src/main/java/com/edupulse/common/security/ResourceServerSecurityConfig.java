package com.edupulse.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource-server configuration shared by ingestion and compliance services.
 * <p>
 * Defense in depth: the gateway already validated the token, but every service validates it
 * again against the auth service JWKS (RS256), so a request that bypasses the gateway on the
 * internal network is still rejected.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class ResourceServerSecurityConfig {

    @Bean
    SecurityFilterChain resourceServerFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/**").hasAuthority("SCOPE_" + Roles.SCOPE_INTERNAL)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new JwtAuthConverter())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${edupulse.security.jwks-uri}") String jwksUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(JwtClaimNames.ISSUER));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
