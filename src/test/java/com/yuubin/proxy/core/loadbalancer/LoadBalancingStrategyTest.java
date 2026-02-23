package com.yuubin.proxy.core.loadbalancer;

import com.yuubin.proxy.core.constants.LoadBalancingType;
import com.yuubin.proxy.entity.Rule;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LoadBalancingStrategyTest {

    @Test
    void testRoundRobin() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1", "t2", "t3"));
        rule.setLoadBalancing(LoadBalancingType.ROUND_ROBIN);

        // First call initializes strategy
        assertEquals("t1", rule.getTarget(null));
        assertEquals("t2", rule.getTarget(null));
        assertEquals("t3", rule.getTarget(null));
        assertEquals("t1", rule.getTarget(null));
    }

    @Test
    void testIpHash() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1", "t2", "t3"));
        rule.setLoadBalancing(LoadBalancingType.IP_HASH);

        String ip1 = "192.168.1.1";
        String target1 = rule.getTarget(ip1);
        assertNotNull(target1);
        assertEquals(target1, rule.getTarget(ip1), "Should be sticky for same IP");

        String ip2 = "192.168.1.2";
        // Verify consistency
        assertEquals(rule.getTarget(ip2), rule.getTarget(ip2));
    }
    
    @Test
    void testEmptyTargets() {
        Rule rule = new Rule();
        rule.setTarget("default");
        // No targets list, so it should return default target
        assertEquals("default", rule.getTarget(null));
    }

    @Test
    void testIpHashWithNullIp() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1", "t2"));
        rule.setLoadBalancing(LoadBalancingType.IP_HASH);
        
        // Should handle null IP gracefully (fallback to first)
        assertNotNull(rule.getTarget(null));
    }

    @Test
    void testSingleTarget() {
        Rule rule = new Rule();
        rule.setTargets(List.of("t1"));
        
        assertEquals("t1", rule.getTarget(null));
        assertEquals("t1", rule.getTarget("1.2.3.4"));
    }

    @Test
    void testNoTargetsAtAll() {
        Rule rule = new Rule();
        assertNull(rule.getTarget(null));
    }
}
