package com.yuubin.proxy.core.proxy;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.exceptions.ProxyException;
import com.yuubin.proxy.core.proxy.impl.http.HttpProxyServer;
import com.yuubin.proxy.core.proxy.impl.socks.Socks4ProxyServer;
import com.yuubin.proxy.core.proxy.impl.socks.Socks5ProxyServer;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.spi.ProxyProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ServiceLoader;

/**
 * Factory for creating specific {@link ProxyServer} implementations based on configuration.
 */
public class ProxyServerFactory {

    private final AuthService authService;
    private final LoggingService loggingService;
    private final MeterRegistry registry;
    private final YuubinProperties globalProps;

    /**
     * Creates a new factory with the required shared services.
     * @param authService The authentication service.
     * @param loggingService The logging service.
     * @param registry The Micrometer meter registry.
     * @param globalProps The global configuration.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ProxyServerFactory(AuthService authService, LoggingService loggingService, 
                           MeterRegistry registry, YuubinProperties globalProps) {
        this.authService = authService;
        this.loggingService = loggingService;
        this.registry = registry;
        this.globalProps = globalProps;
    }

    /**
     * Creates a {@link ProxyServer} instance based on the provided configuration.
     * @param config The proxy configuration.
     * @return A specialized proxy server implementation.
     */
    public ProxyServer create(ProxyServerConfig config) {
        String type = config.getType();
        if (type == null) {
            throw new IllegalArgumentException("Proxy type cannot be null");
        }

        // Built-in types
        if ("HTTP".equalsIgnoreCase(type) || "HTTPS".equalsIgnoreCase(type)) {
            return new HttpProxyServer(config, authService, loggingService, registry, globalProps);
        } else if ("SOCKS4".equalsIgnoreCase(type)) {
            return new Socks4ProxyServer(config, authService, loggingService, registry, globalProps);
        } else if ("SOCKS5".equalsIgnoreCase(type)) {
            return new Socks5ProxyServer(config, authService, loggingService, registry, globalProps);
        }

        // SPI types
        ServiceLoader<ProxyProvider> loader = ServiceLoader.load(ProxyProvider.class);
        for (ProxyProvider provider : loader) {
            if (type.equalsIgnoreCase(provider.getType())) {
                return provider.create(config, authService, loggingService, registry, globalProps);
            }
        }

        throw new ProxyException("Unsupported proxy type: " + type);
    }
}
