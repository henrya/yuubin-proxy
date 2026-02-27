package com.yuubin.proxy.spi;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyServer;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service Provider Interface (SPI) for creating custom Proxy Server
 * implementations.
 * Third-party plugins should implement this interface and register it in
 * META-INF/services/ProxyProvider.
 */
public interface ProxyProvider {
    /**
     * Retrieves the protocol type this provider supports.
     * <p>
     * Example values: "HTTP", "SOCKS5". This corresponds to the 'type' field in the
     * configuration.
     * </p>
     * 
     * @return The supported protocol type (case-insensitive).
     */
    String getType();

    /**
     * Creates a new instance of the proxy server.
     * 
     * @param config   The server configuration.
     * @param auth     The authentication service.
     * @param log      The logging service.
     * @param registry The metrics registry.
     * @param global   The global application properties.
     * @return A new, unstarted ProxyServer instance.
     */
    ProxyServer create(ProxyServerConfig config, AuthService auth, LoggingService log, MeterRegistry registry,
            YuubinProperties global);
}
