package com.yuubin.proxy.entity;

import com.yuubin.proxy.core.constants.LoadBalancingType;
import com.yuubin.proxy.core.loadbalancer.IpHashLoadBalancer;
import com.yuubin.proxy.core.loadbalancer.RoundRobinLoadBalancer;
import com.yuubin.proxy.core.exceptions.ProxyException;
import com.yuubin.proxy.spi.LoadBalancer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Defines a routing rule for an HTTP proxy.
 */
public class Rule {
    /** The host name to match (e.g., abc.com). Null or empty matches any host. */
    private String host;

    /** The path prefix to match (e.g., /api). */
    private String path;

    /** The target URL to forward requests to. */
    private String target;

    /** List of target URLs for load balancing. If provided, 'target' is ignored. */
    private List<String> targets;

    /** Optional custom headers to add to the forwarded request. */
    private Map<String, String> headers;

    /**
     * Whether to treat this as a reverse proxy rule.
     * Volatile ensures visibility when configuration reloads update rules in-place
     * (if applicable)
     * or when accessed across multiple request-handling virtual threads.
     */
    private volatile boolean reverse = false;

    /**
     * Path to check for backend health (e.g., /health). If null, no health checks
     * are performed.
     */
    private String healthCheckPath;

    /** Interval in milliseconds between health checks. Default 10s. */
    private volatile int healthCheckInterval = 10000;

    /** Timeout in milliseconds for each health check request. Default 5s. */
    private volatile int healthCheckTimeout = 5000;

    /** Rate limit in requests per second. <= 0 means unlimited. */
    private volatile double rateLimit = 0;

    /** Burst capacity. If 0, defaults to rateLimit (or 1). */
    private volatile int burst = 0;

    /** Load balancing strategy. Default is ROUND_ROBIN. */
    private volatile LoadBalancingType loadBalancing = LoadBalancingType.ROUND_ROBIN;

    /** Fully qualified class name of custom load balancer (if type is CUSTOM). */
    private volatile String customLoadBalancer;

    /**
     * The instantiated load balancer strategy.
     * Marked transient to avoid serialization by external libraries (e.g.,
     * YAML/JSON)
     * as it is an internal runtime state.
     */
    private final AtomicReference<LoadBalancer> loadBalancerStrategy = new AtomicReference<>();

    /** Tracks targets that are currently failing health checks. */
    private final Set<String> unhealthyTargets = ConcurrentHashMap.newKeySet();

    /**
     * Rate Limiting State (IP -> Bucket).
     * Buckets are lazily created and periodically evicted.
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Resolves a target using the configured load balancing strategy.
     * 
     * @param clientIp The client's IP address (used for IP_HASH).
     * @return The selected target URL.
     */
    public String getTarget(String clientIp) {
        List<String> activeTargets = getHealthyTargets();
        if (activeTargets.isEmpty()) {
            // Fall back to the raw target field when no multi-target list is configured
            return target;
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
     * Resolves a target using the configured strategy (defaulting clientIp to
     * null).
     * 
     * @return The selected target URL.
     */
    public String getTarget() {
        return getTarget(null);
    }

    /**
     * Instantiates the appropriate load balancer based on configuration.
     * 
     * @return A new LoadBalancer instance.
     */
    private LoadBalancer createStrategy() {
        return switch (loadBalancing) {
            case IP_HASH -> new IpHashLoadBalancer();
            case CUSTOM -> {
                if (customLoadBalancer != null && !customLoadBalancer.isBlank()) {
                    try {
                        Class<?> clazz = Class.forName(customLoadBalancer);
                        yield (LoadBalancer) clazz.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        throw new ProxyException("Failed to instantiate custom load balancer: " + customLoadBalancer,
                                e);
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
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        if (healthCheckPath == null) {
            return targets;
        }
        List<String> healthy = targets.stream()
                .filter(t -> !unhealthyTargets.contains(t))
                .toList();
        return healthy.isEmpty() ? targets : healthy; // Fallback to all if all are "unhealthy"
    }

    /**
     * Checks if a request is allowed under the rate limit configuration.
     * Uses the Token Bucket algorithm.
     * 
     * @param clientIp The client IP address.
     * @return true if allowed, false if limit exceeded.
     */
    public boolean allowRequest(String clientIp) {
        if (rateLimit <= 0) {
            return true;
        }

        // Evict stale buckets (idle > 60s) when the map grows beyond 1 000 unique IPs
        // (was 10 000 — lower threshold limits GC pressure under high-unique-IP
        // traffic).
        if (buckets.size() > 1000) {
            long staleThreshold = System.nanoTime() - 60_000_000_000L;
            buckets.values().removeIf(b -> b.lastUsedNano < staleThreshold);
        }
        Bucket bucket = buckets.computeIfAbsent(clientIp,
                k -> new Bucket(rateLimit, burst > 0 ? burst : (int) Math.max(1, rateLimit)));

        return bucket.tryConsume(1);
    }

    /**
     * Sets the single target URL. Also merges it into the {@code targets} list so
     * that
     * {@link #getTarget} only ever reads from one source, eliminating the
     * dual-field ambiguity.
     * 
     * @param target The target URL.
     */
    public void setTarget(String target) {
        this.target = target;
        if (target != null) {
            List<String> current = this.targets != null ? new ArrayList<>(this.targets) : new ArrayList<>();
            if (!current.contains(target)) {
                current.addFirst(target); // Single target first for predictable ordering
            }
            this.targets = Collections.unmodifiableList(current);
        }
    }

    public List<String> getTargets() {
        return targets == null ? null : Collections.unmodifiableList(targets);
    }

    public void setTargets(List<String> targets) {
        this.targets = targets == null ? null : new ArrayList<>(targets);
    }

    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    /**
     * Sets the health check path and validates its format.
     * 
     * @param healthCheckPath The path (e.g., "/health").
     */
    public void setHealthCheckPath(String healthCheckPath) {
        if (healthCheckPath != null) {
            if (!healthCheckPath.startsWith("/")) {
                throw new IllegalArgumentException("healthCheckPath must start with '/': " + healthCheckPath);
            }
            for (String segment : healthCheckPath.split("/", -1)) {
                if ("..".equals(segment)) {
                    throw new IllegalArgumentException(
                            "healthCheckPath must not contain '..' segments: " + healthCheckPath);
                }
            }
        }
        this.healthCheckPath = healthCheckPath;
    }

    public int getHealthCheckInterval() {
        return healthCheckInterval;
    }

    public void setHealthCheckInterval(int healthCheckInterval) {
        this.healthCheckInterval = healthCheckInterval;
    }

    public int getHealthCheckTimeout() {
        return healthCheckTimeout;
    }

    public void setHealthCheckTimeout(int healthCheckTimeout) {
        this.healthCheckTimeout = healthCheckTimeout;
    }

    public double getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(double rateLimit) {
        this.rateLimit = rateLimit;
    }

    public int getBurst() {
        return burst;
    }

    public void setBurst(int burst) {
        this.burst = burst;
    }

    public LoadBalancingType getLoadBalancing() {
        return loadBalancing;
    }

    public void setLoadBalancing(LoadBalancingType loadBalancing) {
        this.loadBalancing = loadBalancing;
    }

    public String getCustomLoadBalancer() {
        return customLoadBalancer;
    }

    public void setCustomLoadBalancer(String customLoadBalancer) {
        this.customLoadBalancer = customLoadBalancer;
    }

    /**
     * Gets all targets configured on this rule (both the single {@code target}
     * field
     * and every entry in {@code targets}), deduplicated. Used by reverse-proxy
     * Location
     * rewriting to match any backend that may have issued a redirect.
     * 
     * @return Unmodifiable list of all target URLs.
     */
    public List<String> getAllTargets() {
        List<String> all = new ArrayList<>();
        if (targets != null) {
            all.addAll(targets);
        }
        if (target != null && !all.contains(target)) {
            all.add(target);
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

    public Map<String, String> getHeaders() {
        return headers == null ? null : Collections.unmodifiableMap(headers);
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? null : new HashMap<>(headers);
    }

    public boolean isReverse() {
        return reverse;
    }

    public void setReverse(boolean reverse) {
        this.reverse = reverse;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Rule rule = (Rule) o;
        return reverse == rule.reverse &&
                Double.compare(rule.rateLimit, rateLimit) == 0 &&
                burst == rule.burst &&
                healthCheckInterval == rule.healthCheckInterval &&
                healthCheckTimeout == rule.healthCheckTimeout &&
                loadBalancing == rule.loadBalancing &&
                Objects.equals(host, rule.host) &&
                Objects.equals(path, rule.path) &&
                Objects.equals(target, rule.target) &&
                Objects.equals(targets, rule.targets) &&
                Objects.equals(healthCheckPath, rule.healthCheckPath) &&
                Objects.equals(customLoadBalancer, rule.customLoadBalancer) &&
                Objects.equals(headers, rule.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, path, target, targets, headers, reverse, rateLimit, burst,
                loadBalancing, healthCheckPath, healthCheckInterval, healthCheckTimeout, customLoadBalancer);
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
         * Used for stale-entry eviction. Marked volatile for thread-safe reading by
         * eviction logic.
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
