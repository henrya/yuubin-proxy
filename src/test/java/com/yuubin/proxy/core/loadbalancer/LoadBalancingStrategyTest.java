package com.yuubin.proxy.core.loadbalancer;

import com.yuubin.proxy.core.constants.LoadBalancingType;
import com.yuubin.proxy.core.proxy.impl.http.rules.RuleRuntime;
import com.yuubin.proxy.entity.Rule;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for load balancing strategies via {@link RuleRuntime}.
 */
class LoadBalancingStrategyTest {

    @Test
    void testRoundRobin() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1", "t2", "t3"));
        rule.setLoadBalancing(LoadBalancingType.ROUND_ROBIN);

        RuleRuntime runtime = new RuleRuntime(rule);
        // First call initializes strategy
        assertEquals("t1", runtime.resolveTarget(null));
        assertEquals("t2", runtime.resolveTarget(null));
        assertEquals("t3", runtime.resolveTarget(null));
        assertEquals("t1", runtime.resolveTarget(null));
    }

    @Test
    void testIpHash() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1", "t2", "t3"));
        rule.setLoadBalancing(LoadBalancingType.IP_HASH);

        RuleRuntime runtime = new RuleRuntime(rule);
        String ip1 = "192.168.1.1";
        String target1 = runtime.resolveTarget(ip1);
        assertNotNull(target1);
        assertEquals(target1, runtime.resolveTarget(ip1), "Should be sticky for same IP");

        String ip2 = "192.168.1.2";
        // Verify consistency
        assertEquals(runtime.resolveTarget(ip2), runtime.resolveTarget(ip2));
    }

    @Test
    void testEmptyTargets() {
        Rule rule = new Rule();
        rule.setTarget("default");
        // No targets list, so it should return default target
        RuleRuntime runtime = new RuleRuntime(rule);
        assertEquals("default", runtime.resolveTarget(null));
    }

    @Test
    void testIpHashWithNullIp() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1", "t2"));
        rule.setLoadBalancing(LoadBalancingType.IP_HASH);

        RuleRuntime runtime = new RuleRuntime(rule);
        // Should handle null IP gracefully (fallback to first)
        assertNotNull(runtime.resolveTarget(null));
    }

    @Test
    void testSingleTarget() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1"));

        RuleRuntime runtime = new RuleRuntime(rule);
        assertEquals("t1", runtime.resolveTarget(null));
        assertEquals("t1", runtime.resolveTarget("1.2.3.4"));
    }

    @Test
    void testNoTargetsAtAll() {
        Rule rule = new Rule();
        RuleRuntime runtime = new RuleRuntime(rule);
        assertNull(runtime.resolveTarget(null));
    }
}
