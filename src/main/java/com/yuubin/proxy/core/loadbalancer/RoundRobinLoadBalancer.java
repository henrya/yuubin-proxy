package com.yuubin.proxy.core.loadbalancer;

import com.yuubin.proxy.spi.LoadBalancer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standard Round-Robin load balancing strategy.
 */
public class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public String select(List<String> targets, String clientIp) {
        if (targets.isEmpty()) {
            return null;
        }
        int index = (counter.getAndIncrement() & 0x7FFFFFFF) % targets.size();
        return targets.get(index);
    }
}
