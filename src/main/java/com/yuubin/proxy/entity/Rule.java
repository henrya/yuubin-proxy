package com.yuubin.proxy.entity;

import com.yuubin.proxy.core.constants.LoadBalancingType;
import com.yuubin.proxy.config.UpstreamProxyConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defines a routing rule for an HTTP proxy.
 * This is a pure configuration POJO mapped from YAML; runtime behaviour
 * (load balancing, rate limiting, health tracking) lives in
 * {@link com.yuubin.proxy.core.proxy.impl.http.rules.RuleRuntime}.
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

    /** Upstream proxy to use when this rule matches. */
    private UpstreamProxyConfig upstreamProxy;

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

    /** Rate limit in requests per second. &lt;= 0 means unlimited. */
    private volatile double rateLimit = 0;

    /** Burst capacity. If 0, defaults to rateLimit (or 1). */
    private volatile int burst = 0;

    /** Load balancing strategy. Default is ROUND_ROBIN. */
    private volatile LoadBalancingType loadBalancing = LoadBalancingType.ROUND_ROBIN;

    /** Fully qualified class name of custom load balancer (if type is CUSTOM). */
    private volatile String customLoadBalancer;

    /**
     * Returns the host name pattern for this rule.
     *
     * @return The host name, or null if any host matches.
     */
    public String getHost() {
        return host;
    }

    /**
     * Sets the host name pattern for this rule.
     *
     * @param host The host name to match.
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * Returns the path prefix for this rule.
     *
     * @return The path prefix.
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the path prefix for this rule.
     *
     * @param path The path prefix to match.
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Returns the single target URL.
     *
     * @return The target URL, or null if not set.
     */
    public String getTarget() {
        return target;
    }

    /**
     * Sets the single target URL.
     *
     * @param target The target URL.
     */
    public void setTarget(String target) {
        this.target = target;
    }

    /**
     * Returns the list of target URLs for load balancing.
     *
     * @return An unmodifiable list of targets, or null.
     */
    public List<String> getTargets() {
        return targets == null ? null : Collections.unmodifiableList(targets);
    }

    /**
     * Sets the list of target URLs for load balancing.
     *
     * @param targets The target URL list.
     */
    public void setTargets(List<String> targets) {
        this.targets = targets == null ? null : new ArrayList<>(targets);
    }

    /**
     * Returns the health check path.
     *
     * @return The health check path, or null if not configured.
     */
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

    /**
     * Returns the health check interval in milliseconds.
     *
     * @return The interval.
     */
    public int getHealthCheckInterval() {
        return healthCheckInterval;
    }

    /**
     * Sets the health check interval in milliseconds.
     *
     * @param healthCheckInterval The interval.
     */
    public void setHealthCheckInterval(int healthCheckInterval) {
        this.healthCheckInterval = healthCheckInterval;
    }

    /**
     * Returns the health check timeout in milliseconds.
     *
     * @return The timeout.
     */
    public int getHealthCheckTimeout() {
        return healthCheckTimeout;
    }

    /**
     * Sets the health check timeout in milliseconds.
     *
     * @param healthCheckTimeout The timeout.
     */
    public void setHealthCheckTimeout(int healthCheckTimeout) {
        this.healthCheckTimeout = healthCheckTimeout;
    }

    /**
     * Returns the rate limit in requests per second.
     *
     * @return The rate limit; &lt;= 0 means unlimited.
     */
    public double getRateLimit() {
        return rateLimit;
    }

    /**
     * Sets the rate limit in requests per second.
     *
     * @param rateLimit The rate limit.
     */
    public void setRateLimit(double rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * Returns the burst capacity.
     *
     * @return The burst value.
     */
    public int getBurst() {
        return burst;
    }

    /**
     * Sets the burst capacity.
     *
     * @param burst The burst value.
     */
    public void setBurst(int burst) {
        this.burst = burst;
    }

    /**
     * Returns the load balancing strategy.
     *
     * @return The load balancing type.
     */
    public LoadBalancingType getLoadBalancing() {
        return loadBalancing;
    }

    /**
     * Sets the load balancing strategy.
     *
     * @param loadBalancing The load balancing type.
     */
    public void setLoadBalancing(LoadBalancingType loadBalancing) {
        this.loadBalancing = loadBalancing;
    }

    /**
     * Returns the custom load balancer class name.
     *
     * @return The fully qualified class name, or null.
     */
    public String getCustomLoadBalancer() {
        return customLoadBalancer;
    }

    /**
     * Sets the custom load balancer class name.
     *
     * @param customLoadBalancer The fully qualified class name.
     */
    public void setCustomLoadBalancer(String customLoadBalancer) {
        this.customLoadBalancer = customLoadBalancer;
    }

    /**
     * Returns the custom headers to add to forwarded requests.
     *
     * @return An unmodifiable map of headers, or null.
     */
    public Map<String, String> getHeaders() {
        return headers == null ? null : Collections.unmodifiableMap(headers);
    }

    /**
     * Sets the custom headers to add to forwarded requests.
     *
     * @param headers The header map.
     */
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? null : new HashMap<>(headers);
    }

    /**
     * Returns the upstream proxy configuration.
     *
     * @return A defensive copy of the upstream proxy config, or null.
     */
    public UpstreamProxyConfig getUpstreamProxy() {
        return upstreamProxy == null ? null : new UpstreamProxyConfig(upstreamProxy);
    }

    /**
     * Sets the upstream proxy configuration.
     *
     * @param upstreamProxy The upstream proxy config.
     */
    public void setUpstreamProxy(UpstreamProxyConfig upstreamProxy) {
        this.upstreamProxy = upstreamProxy == null ? null
                : new UpstreamProxyConfig(upstreamProxy);
    }

    /**
     * Returns whether this is a reverse proxy rule.
     *
     * @return true if reverse proxying is enabled.
     */
    public boolean isReverse() {
        return reverse;
    }

    /**
     * Sets whether this is a reverse proxy rule.
     *
     * @param reverse true to enable reverse proxying.
     */
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
                Objects.equals(headers, rule.headers) &&
                Objects.equals(upstreamProxy, rule.upstreamProxy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, path, target, targets, headers, reverse, rateLimit, burst,
                loadBalancing, healthCheckPath, healthCheckInterval, healthCheckTimeout, customLoadBalancer,
                upstreamProxy);
    }
}
