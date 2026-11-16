package com.edupulse.compliance.gap.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service calls (NFR "all inter-service communication authorized with JWT").
 * Obtains a client-credentials token ({@code scope=internal}) from the auth service, caches it until
 * shortly before expiry, and calls the ingestion service's {@code /internal/**} API.
 * Instances are resolved through Eureka with the Spring Cloud LoadBalancer.
 */
@Slf4j
@Component
public class ServiceClients {

    private final LoadBalancerClient loadBalancer;
    private final RestClient http = RestClient.create();
    private final String clientId;
    private final String clientSecret;

    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public ServiceClients(LoadBalancerClient loadBalancer,
                          @Value("${edupulse.service-client.id:compliance-service}") String clientId,
                          @Value("${edupulse.service-client.secret}") String clientSecret) {
        this.loadBalancer = loadBalancer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public record DocumentInfo(UUID id, UUID organizationId, UUID orgUnitId, String title, String filename,
                               String category, int clearanceLevel, String status) {
    }

    public record Parent(UUID parentId, int parentIndex, Integer pageNumber, String sectionRef, String content) {
    }

    public DocumentInfo document(UUID id) {
        return http.get().uri(base("ingestion-service").resolve("/internal/documents/" + id))
                .header("Authorization", "Bearer " + serviceToken())
                .retrieve().body(DocumentInfo.class);
    }

    public List<Parent> parents(UUID id) {
        return http.get().uri(base("ingestion-service").resolve("/internal/documents/" + id + "/parents"))
                .header("Authorization", "Bearer " + serviceToken())
                .retrieve().body(new ParameterizedTypeReference<>() {
                });
    }

    synchronized String serviceToken() {
        if (token != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return token;
        }
        Map<String, Object> body = http.post().uri(base("auth-service").resolve("/api/auth/service-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("clientId", clientId, "clientSecret", clientSecret))
                .retrieve().body(new ParameterizedTypeReference<>() {
                });
        if (body == null || body.get("accessToken") == null) {
            throw new IllegalStateException("Auth service returned no service token");
        }
        token = String.valueOf(body.get("accessToken"));
        long expiresIn = body.get("expiresIn") instanceof Number n ? n.longValue() : 300;
        tokenExpiry = Instant.now().plusSeconds(expiresIn);
        log.debug("Obtained service token (expires in {}s)", expiresIn);
        return token;
    }

    private URI base(String serviceId) {
        ServiceInstance instance = loadBalancer.choose(serviceId);
        if (instance == null) {
            throw new IllegalStateException("No instance of " + serviceId + " registered in Eureka");
        }
        return instance.getUri();
    }
}
