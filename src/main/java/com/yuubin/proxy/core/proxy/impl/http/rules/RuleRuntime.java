package com.yuubin.proxy.core.proxy.impl.http.rules;

import com.yuubin.proxy.core.exceptions.ProxyException;
import com.yuubin.proxy.core.loadbalancer.IpHashLoadBalancer;
import com.yuubin.proxy.core.loadbalancer.RoundRobinLoadBalancer;
import com.yuubin.proxy.entity.Rule;
import com.yuubin.proxy.spi.LoadBalancer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the runtime state for a single {@link Rule}.
 * Encapsulates load balancing, health tracking, and rate limiting logic
 * that does not belong in the entity POJO.
 */
public class RuleRuntime {

    private final Rule rule;

    /** The instantiated load balancer strategy (lazily created). */
    private final AtomicReference<LoadBalancer> loadBalancerStrategy = new AtomicReference<>();

    /** Tracks targets that are currently failing health checks. */
    private final Set<String> unhealthyTargets = ConcurrentHashMap.newKeySet();

    /**
     * Rate limiting state (IP → Bucket).
     * Buckets are lazily created and periodically evicted.
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Creates a new RuleRuntime wrapping the given rule.
     *
     * @param rule The routing rule to manage runtime state for.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public RuleRuntime(Rule rule) {
        this.rule = rule;
    }

    /**
     * Returns the underlying rule.
     *
     * @return The wrapped rule.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP")
    public Rule getRule() {
        return rule;
    }

    /**
     * Resolves a target using the configured load balancing strategy.
     *
     * @param clientIp The client's IP address (used for IP_HASH).
     * @return The selected target URL.
     */
    public String resolveTarget(String clientIp) {
        List<String> activeTargets = getHealthyTargets();
        if (activeTargets.isEmpty()) {
            // Fall back to the raw target field when no multi-target list is configured
            return rule.getTarget();
        }

        LoadBalancer strategy = loadBalancerStrategy.get();
        if (strategy == null) {
            strategy = createStrategy();
            if (!loadBalancerStrategy.compareAndSet(null, strategy)) {
                strategy = loadBalancerStrategy.get();
            }
        }
        return strategy.select(activeTargets, clientIp);
    }

    /**
     * Instantiates the appropriate load balancer based on configuration.
     *
     * @return A new LoadBalancer instance.
     */
    private LoadBalancer createStrategy() {
        return switch (rule.getLoadBalancing()) {
            case IP_HASH -> new IpHashLoadBalancer();
            case CUSTOM -> {
                String customClass = rule.getCustomLoadBalancer();
                if (customClass != null && !customClass.isBlank()) {
                    try {
                        Class<?> clazz = Class.forName(customClass);
                        yield (LoadBalancer) clazz.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        throw new ProxyException("Failed to instantiate custom load balancer: " + customClass, e);
                    }
                } else {
                    yield new RoundRobinLoadBalancer();
                }
            }
            default -> new RoundRobinLoadBalancer();
        };
    }

    /**
     * Filters targets to only include those that are currently healthy.
     * If all targets are unhealthy, falls back to returning all of them.
     *
     * @return List of healthy target URLs.
     */
    private List<String> getHealthyTargets() {
        List<String> targets = rule.getTargets();
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        if (rule.getHealthCheckPath() == null) {
            return targets;
        }
        List<String> healthy = targets.stream()
                .filter(t -> !unhealthyTargets.contains(t))
                .toList();
        return healthy.isEmpty() ? targets : healthy;
    }

    /**
     * Checks if a request is allowed under the rate limit configuration.
     * Uses the Token Bucket algorithm.
     *
     * @param clientIp The client IP address.
     * @return true if allowed, false if limit exceeded.
     */
    public boolean allowRequest(String clientIp) {
        double rateLimit = rule.getRateLimit();
        if (rateLimit <= 0) {
            return true;
        }

        // Evict stale buckets (idle > 60s) when the map grows beyond 1000 unique IPs
        if (buckets.size() > 1000) {
            long staleThreshold = System.nanoTime() - 60_000_000_000L;
            buckets.values().removeIf(b -> b.lastUsedNano < staleThreshold);
        }
        int burstVal = rule.getBurst();
        Bucket bucket = buckets.computeIfAbsent(clientIp,
                k -> new Bucket(rateLimit, burstVal > 0 ? burstVal : (int) Math.max(1, rateLimit)));

        return bucket.tryConsume(1);
    }

    /**
     * Gets all targets configured on the rule (both the single {@code target}
     * field and every entry in {@code targets}), deduplicated.
     *
     * @return Unmodifiable list of all target URLs.
     */
    public List<String> getAllTargets() {
        List<String> all = new ArrayList<>();
        List<String> targets = rule.getTargets();
        if (targets != null) {
            all.addAll(targets);
        }
        String single = rule.getTarget();
        if (single != null && !all.contains(single)) {
            all.add(single);
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Marks a target as unhealthy.
     *
     * @param target The target URL.
     */
    public void markUnhealthy(String target) {
        unhealthyTargets.add(target);
    }

    /**
     * Marks a target as healthy.
     *
     * @param target The target URL.
     */
    public void markHealthy(String target) {
        unhealthyTargets.remove(target);
    }

    /**
     * Simple thread-safe Token Bucket implementation.
     */
    private static class Bucket {
        /** Maximum capacity of the bucket. */
        private final double capacity;
        /** Token generation rate per nanosecond. */
        private final double tokensPerNano;

        /** Current available tokens. */
        private double tokens;
        /** Timestamp of the last refill operation. */
        private long lastRefillTime;
        /**
         * Last time this bucket was accessed, in nanoseconds.
         * Used for stale-entry eviction.
         */
        volatile long lastUsedNano;

        /**
         * Creates a new Bucket.
         *
         * @param rate     Token refill rate per second.
         * @param capacity Maximum tokens the bucket can hold.
         */
        Bucket(double rate, double capacity) {
            this.capacity = capacity;
            this.tokensPerNano = rate / 1_000_000_000.0;
            this.tokens = capacity;
            this.lastRefillTime = System.nanoTime();
            this.lastUsedNano = this.lastRefillTime;
        }

        /**
         * Attempts to consume tokens from the bucket.
         *
         * @param cost Number of tokens to consume.
         * @return true if successful, false otherwise.
         */
        synchronized boolean tryConsume(double cost) {
            lastUsedNano = System.nanoTime();
            refill();
            if (tokens >= cost) {
                tokens -= cost;
                return true;
            }
            return false;
        }

        /**
         * Refills the bucket based on elapsed time.
         */
        private void refill() {
            long now = System.nanoTime();
            long delta = now - lastRefillTime;
            if (delta > 0) {
                double newTokens = delta * tokensPerNano;
                tokens = Math.min(capacity, tokens + newTokens);
                lastRefillTime = now;
            }
        }
    }
}
