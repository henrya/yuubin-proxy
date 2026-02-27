package com.yuubin.proxy.core.proxy.impl.http.rules;

import com.yuubin.proxy.core.constants.LoadBalancingType;
import com.yuubin.proxy.entity.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dedicated tests for {@link RuleRuntime}.
 */
class RuleRuntimeTest {

    @Test
    void resolveTarget_roundRobin_rotatesThroughTargets() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1", "t2", "t3"));
        rule.setLoadBalancing(LoadBalancingType.ROUND_ROBIN);

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.resolveTarget(null)).isEqualTo("t1");
        assertThat(runtime.resolveTarget(null)).isEqualTo("t2");
        assertThat(runtime.resolveTarget(null)).isEqualTo("t3");
        assertThat(runtime.resolveTarget(null)).isEqualTo("t1");
    }

    @Test
    void resolveTarget_ipHash_stickyForSameIp() {
        Rule rule = new Rule();
        rule.setTargets(List.of("a", "b", "c"));
        rule.setLoadBalancing(LoadBalancingType.IP_HASH);

        RuleRuntime runtime = new RuleRuntime(rule);
        String first = runtime.resolveTarget("10.0.0.1");
        assertThat(first).isNotNull();
        assertThat(runtime.resolveTarget("10.0.0.1")).isEqualTo(first);
        assertThat(runtime.resolveTarget("10.0.0.1")).isEqualTo(first);
    }

    @Test
    void resolveTarget_noTargetsList_fallsBackToSingleTarget() {
        Rule rule = new Rule();
        rule.setTarget("http://backend:8080");

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.resolveTarget(null)).isEqualTo("http://backend:8080");
    }

    @Test
    void resolveTarget_noTargetsAtAll_returnsNull() {
        Rule rule = new Rule();
        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.resolveTarget(null)).isNull();
    }

    @Test
    void resolveTarget_customLoadBalancer_instantiatesClass() {
        Rule rule = new Rule();
        rule.setTargets(List.of("x"));
        rule.setLoadBalancing(LoadBalancingType.CUSTOM);
        rule.setCustomLoadBalancer("com.yuubin.proxy.core.loadbalancer.RoundRobinLoadBalancer");

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.resolveTarget(null)).isEqualTo("x");
    }

    @Test
    void resolveTarget_customLoadBalancer_throwsForInvalidClass() {
        Rule rule = new Rule();
        rule.setTargets(List.of("x"));
        rule.setLoadBalancing(LoadBalancingType.CUSTOM);
        rule.setCustomLoadBalancer("nonexistent.ClassName");

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThatThrownBy(() -> runtime.resolveTarget(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to instantiate");
    }

    @Test
    void healthTracking_excludesUnhealthyTargets() {
        Rule rule = new Rule();
        rule.setTargets(List.of("h1", "h2"));
        rule.setHealthCheckPath("/health");

        RuleRuntime runtime = new RuleRuntime(rule);
        runtime.markUnhealthy("h1");
        assertThat(runtime.resolveTarget(null)).isEqualTo("h2");
    }

    @Test
    void healthTracking_fallsBackToAllWhenAllUnhealthy() {
        Rule rule = new Rule();
        rule.setTargets(List.of("h1", "h2"));
        rule.setHealthCheckPath("/health");

        RuleRuntime runtime = new RuleRuntime(rule);
        runtime.markUnhealthy("h1");
        runtime.markUnhealthy("h2");
        assertThat(runtime.resolveTarget(null)).isIn("h1", "h2");
    }

    @Test
    void healthTracking_restoresHealthyTarget() {
        Rule rule = new Rule();
        rule.setTargets(List.of("h1", "h2"));
        rule.setHealthCheckPath("/health");

        RuleRuntime runtime = new RuleRuntime(rule);
        runtime.markUnhealthy("h1");
        runtime.markHealthy("h1");
        assertThat(runtime.resolveTarget(null)).isIn("h1", "h2");
    }

    @Test
    void allowRequest_respectsRateLimit() {
        Rule rule = new Rule();
        rule.setRateLimit(1.0);
        rule.setBurst(1);

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.allowRequest("ip1")).isTrue();
        assertThat(runtime.allowRequest("ip1")).isFalse();
    }

    @Test
    void allowRequest_separateBucketsPerIp() {
        Rule rule = new Rule();
        rule.setRateLimit(1.0);
        rule.setBurst(1);

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.allowRequest("a")).isTrue();
        assertThat(runtime.allowRequest("b")).isTrue();
        assertThat(runtime.allowRequest("a")).isFalse();
        assertThat(runtime.allowRequest("b")).isFalse();
    }

    @Test
    void allowRequest_unlimitedWhenRateZero() {
        Rule rule = new Rule();
        rule.setRateLimit(0);

        RuleRuntime runtime = new RuleRuntime(rule);
        for (int i = 0; i < 100; i++) {
            assertThat(runtime.allowRequest("ip")).isTrue();
        }
    }

    @Test
    void allowRequest_evictsStaleEntries() {
        Rule rule = new Rule();
        rule.setRateLimit(1.0);

        RuleRuntime runtime = new RuleRuntime(rule);
        for (int i = 0; i < 1100; i++) {
            runtime.allowRequest("ip" + i);
        }
        // Trigger eviction (>1000 entries)
        assertThat(runtime.allowRequest("fresh-ip")).isTrue();
    }

    @Test
    void getAllTargets_combinesSingleAndMulti() {
        Rule rule = new Rule();
        rule.setTarget("http://single");
        rule.setTargets(List.of("http://a", "http://b"));

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.getAllTargets()).containsExactlyInAnyOrder("http://single", "http://a", "http://b");
    }

    @Test
    void getAllTargets_deduplicates() {
        Rule rule = new Rule();
        rule.setTarget("http://a");
        rule.setTargets(List.of("http://a", "http://b"));

        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.getAllTargets()).containsExactly("http://a", "http://b");
    }

    @Test
    void getAllTargets_empty_whenNoTargets() {
        Rule rule = new Rule();
        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.getAllTargets()).isEmpty();
    }

    @Test
    void getRule_returnsWrappedRule() {
        Rule rule = new Rule();
        rule.setPath("/api");
        RuleRuntime runtime = new RuleRuntime(rule);
        assertThat(runtime.getRule()).isSameAs(rule);
    }
}
