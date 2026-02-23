package com.yuubin.proxy.core.proxy;

import com.yuubin.proxy.config.ProxyServerConfig;

/**
 * Interface representing a proxy server instance.
 */
public interface ProxyServer {
    /**
     * Starts the proxy server and begins listening for connections.
     */
    void start();
    
    /**
     * Stops the proxy server and releases all associated resources.
     */
    void stop();
    
    /**
     * Retrieves the proxy server configuration.
     * @return The configuration used by this proxy server.
     */
    ProxyServerConfig getConfig();
}
