package com.yuubin.proxy.core.constants;

/**
 * Supported load balancing algorithms for backend target selection.
 */
public enum LoadBalancingType {
    /**
     * Cycle through targets in order. Default.
     */
    ROUND_ROBIN,

    /**
     * Consistent hashing based on client IP address.
     * Ensures a client sticks to the same backend server.
     */
    IP_HASH,

    /**
     * Use a custom strategy defined by 'customLoadBalancer' class name or alias.
     */
    CUSTOM
}
