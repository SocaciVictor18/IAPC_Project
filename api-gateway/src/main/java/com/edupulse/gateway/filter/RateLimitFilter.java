package com.edupulse.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory token-bucket rate limiter (per authenticated user, or per client IP for anonymous calls).
 * Good enough for the demo; a Redis-backed {@code RequestRateLimiter} would be used when several
 * gateway replicas must share one budget.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double refillPerSecond;

    public RateLimitFilter(@Value("${edupulse.rate-limit.capacity:120}") double capacity,
                           @Value("${edupulse.rate-limit.refill-per-second:10}") double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(a -> "u:" + a.getName())
                .defaultIfEmpty("ip:" + clientIp(exchange))
                .flatMap(key -> {
                    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
                    if (bucket.tryConsume(capacity, refillPerSecond)) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                    return exchange.getResponse().setComplete();
                });
    }

    private static String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    static final class Bucket {
        private final ReentrantLock lock = new ReentrantLock();
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        Bucket(double tokens) {
            this.tokens = tokens;
        }

        boolean tryConsume(double capacity, double refillPerSecond) {
            lock.lock();
            try {
                long now = System.nanoTime();
                tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 1e9 * refillPerSecond);
                lastRefillNanos = now;
                if (tokens >= 1) {
                    tokens -= 1;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
    }
}
