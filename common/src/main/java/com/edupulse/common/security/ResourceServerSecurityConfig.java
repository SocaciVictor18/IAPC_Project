package com.edupulse.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT security shared by the ingestion and compliance services.
 * The gateway already validated the token, but each service checks it again against the
 * auth service's public key (JWKS), so a request that bypasses the gateway is still rejected.
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
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new JwtAuthConverter())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${edupulse.security.jwks-uri}") String jwksUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtClaimNames.ISSUER));
        return decoder;
    }
}
