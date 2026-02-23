package com.yuubin.proxy.spi;

import java.util.List;

/**
 * Strategy interface for load balancing traffic among multiple targets.
 * Implementations can be stateful (e.g. Round Robin counter).
 */
public interface LoadBalancer {
    /**
     * Selects a target from the list of healthy targets.
     * @param targets List of healthy target URLs.
     * @param clientIp Client IP address (optional, for sticky sessions).
     * @return Selected target URL.
     */
    String select(List<String> targets, String clientIp);
}
