package com.yuubin.proxy.entity;

import com.yuubin.proxy.core.constants.LoadBalancingType;
import com.yuubin.proxy.core.proxy.impl.http.rules.RuleRuntime;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link Rule} entity POJO.
 */
class RuleTest {

    @Test
    void testGettersAndSetters() {
        Rule rule = new Rule();
        rule.setHost("example.com");
        rule.setPath("/api");
        rule.setTarget("http://backend:8080");
        rule.setTargets(List.of("http://b1", "http://b2"));
        rule.setHeaders(Map.of("X-Test", "value"));
        rule.setReverse(true);
        rule.setRateLimit(10.0);
        rule.setBurst(20);
        rule.setLoadBalancing(LoadBalancingType.IP_HASH);
        rule.setHealthCheckInterval(5000);
        rule.setHealthCheckTimeout(2000);

        com.yuubin.proxy.config.UpstreamProxyConfig upstream = new com.yuubin.proxy.config.UpstreamProxyConfig();
        upstream.setHost("proxy.corp");
        upstream.setPort(8080);
        rule.setUpstreamProxy(upstream);

        assertThat(rule.getHost()).isEqualTo("example.com");
        assertThat(rule.getPath()).isEqualTo("/api");
        assertThat(rule.getTarget()).isEqualTo("http://backend:8080");
        assertThat(rule.getTargets()).containsExactly("http://b1", "http://b2");
        assertThat(rule.getHeaders()).containsEntry("X-Test", "value");
        assertThat(rule.isReverse()).isTrue();
        assertThat(rule.getRateLimit()).isEqualTo(10.0);
        assertThat(rule.getBurst()).isEqualTo(20);
        assertThat(rule.getLoadBalancing()).isEqualTo(LoadBalancingType.IP_HASH);
        assertThat(rule.getHealthCheckInterval()).isEqualTo(5000);
        assertThat(rule.getHealthCheckTimeout()).isEqualTo(2000);
        assertThat(rule.getUpstreamProxy()).isEqualTo(upstream);
    }

    @Test
    void testHealthCheckPathValidation() {
        Rule rule = new Rule();
        assertThatThrownBy(() -> rule.setHealthCheckPath("health"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '/'");

        assertThatThrownBy(() -> rule.setHealthCheckPath("/../admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain '..'");

        rule.setHealthCheckPath("/health");
        assertThat(rule.getHealthCheckPath()).isEqualTo("/health");

        rule.setHealthCheckPath(null);
        assertThat(rule.getHealthCheckPath()).isNull();
    }

    @Test
    void testRateLimiting() throws InterruptedException {
        Rule rule = new Rule();
        rule.setRateLimit(1.0); // 1 req/sec
        rule.setBurst(1);

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.allowRequest("ip1")).isTrue();
        assertThat(runtime.allowRequest("ip1")).isFalse();

        // Different IP should have its own bucket
        assertThat(runtime.allowRequest("ip2")).isTrue();
    }

    @Test
    void testBucketEviction() {
        Rule rule = new Rule();
        rule.setRateLimit(1.0);

        RuleRuntime runtime = new RuleRuntime(rule);
        // Fill up buckets
        for (int i = 0; i < 10001; i++) {
            runtime.allowRequest("ip" + i);
        }

        // This should trigger eviction if any are stale (none are yet, but we test the
        // branch)
        assertThat(runtime.allowRequest("ip-new")).isTrue();
    }

    @Test
    void testHealthyTargetsFallback() {
        Rule rule = new Rule();
        rule.setTargets(List.of("http://b1", "http://b2"));
        rule.setHealthCheckPath("/health");

        RuleRuntime runtime = new RuleRuntime(rule);
        runtime.markUnhealthy("http://b1");
        assertThat(runtime.resolveTarget(null)).isEqualTo("http://b2");

        runtime.markUnhealthy("http://b2");
        // Fallback to all if all are unhealthy
        assertThat(runtime.resolveTarget(null)).isIn("http://b1", "http://b2");

        runtime.markHealthy("http://b1");
        assertThat(runtime.resolveTarget(null)).isEqualTo("http://b1");
    }

    @Test
    void testCustomLoadBalancer() {
        Rule rule = new Rule();
        rule.setTargets(List.of("http://b1"));
        rule.setLoadBalancing(LoadBalancingType.CUSTOM);
        rule.setCustomLoadBalancer("com.yuubin.proxy.core.loadbalancer.RoundRobinLoadBalancer");

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.resolveTarget(null)).isEqualTo("http://b1");

        Rule rule2 = new Rule();
        rule2.setTargets(List.of("http://b1"));
        rule2.setLoadBalancing(LoadBalancingType.CUSTOM);
        rule2.setCustomLoadBalancer("invalid.ClassName");
        RuleRuntime runtime2 = new RuleRuntime(rule2);
        assertThatThrownBy(() -> runtime2.resolveTarget(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to instantiate");
    }

    @Test
    void testGetAllTargets() {
        Rule rule = new Rule();
        rule.setTarget("http://single");
        rule.setTargets(List.of("http://b1", "http://b2"));

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.getAllTargets()).containsExactlyInAnyOrder("http://single", "http://b1", "http://b2");
    }

    @Test
    void testEqualsAndHashCode() {
        Rule r1 = new Rule();
        r1.setHost("h");
        Rule r2 = new Rule();
        r2.setHost("h");

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());

        r2.setHost("h2");
        assertThat(r1).isNotEqualTo(r2);

        r2.setHost("h"); // reset
        com.yuubin.proxy.config.UpstreamProxyConfig upstream = new com.yuubin.proxy.config.UpstreamProxyConfig();
        upstream.setHost("foo");
        r1.setUpstreamProxy(upstream);
        assertThat(r1).isNotEqualTo(r2);

        com.yuubin.proxy.config.UpstreamProxyConfig upstream2 = new com.yuubin.proxy.config.UpstreamProxyConfig();
        upstream2.setHost("foo");
        r2.setUpstreamProxy(upstream2);
        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }
}
