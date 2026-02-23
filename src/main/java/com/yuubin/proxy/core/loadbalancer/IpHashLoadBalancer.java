package com.yuubin.proxy.core.loadbalancer;

import com.yuubin.proxy.spi.LoadBalancer;
import java.util.List;

/**
 * Consistent hashing strategy based on Client IP.
 */
public class IpHashLoadBalancer implements LoadBalancer {
    @Override
    public String select(List<String> targets, String clientIp) {
        if (targets.isEmpty()) {
            return null;
        }
        if (clientIp == null) {
            // Fallback to first if IP is missing (should verify context)
            return targets.getFirst();
        }
        int hash = clientIp.hashCode();
        // Ensure positive hash and map to a valid target index
        // 0x7FFFFFFF - max value of 32 bit integer
        int index = (hash & 0x7FFFFFFF) % targets.size();
        return targets.get(index);
    }
}
