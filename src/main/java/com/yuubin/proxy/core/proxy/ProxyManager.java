package com.yuubin.proxy.core.proxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.exceptions.ProxyException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates multiple proxy server instances.
 * Manages starting, stopping, and dynamic reloading of servers.
 */
public class ProxyManager {

    private static final Logger log = LoggerFactory.getLogger(ProxyManager.class);

    private final YuubinProperties properties;
    private final ProxyServerFactory factory;
    private final Map<String, ProxyServer> activeServers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates a ProxyManager with the specified configuration and factory.
     * 
     * @param properties Root configuration properties.
     * @param factory    Factory to create server instances.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ProxyManager(YuubinProperties properties, ProxyServerFactory factory) {
        this.properties = properties;
        this.factory = factory;
    }

    /**
     * Starts all proxy servers defined in the initial configuration.
     */
    public void startServers() {
        refreshServers(properties.getProxies());
    }

    /**
     * Dynamically updates the set of running proxy servers to match a new
     * configuration.
     * Correctly handles additions, removals, and modifications of servers.
     * 
     * @param newConfigs The updated list of proxy server configurations.
     */
    public synchronized void refreshServers(List<ProxyServerConfig> newConfigs) {
        if (newConfigs == null) {
            newConfigs = List.of();
        }

        Map<String, ProxyServerConfig> newConfigMap = new HashMap<>();
        for (ProxyServerConfig config : newConfigs) {
            String key = config.getName() != null ? config.getName() : String.valueOf(config.getPort());
            newConfigMap.put(key, config);
        }

        // 1. Stop and remove servers that are no longer in the config
        for (String activeKey : new ArrayList<>(activeServers.keySet())) {
            if (!newConfigMap.containsKey(activeKey)) {
                stopServer(activeKey);
            }
        }

        // 2. Add or update servers
        for (Map.Entry<String, ProxyServerConfig> entry : newConfigMap.entrySet()) {
            String key = entry.getKey();
            ProxyServerConfig newConfig = entry.getValue();

            if (activeServers.containsKey(key)) {
                ProxyServer activeServer = activeServers.get(key);
                // Deep equality check to see if we need to restart
                if (!activeServer.getConfig().equals(newConfig)) {
                    log.info("Configuration changed for proxy {}. Restarting...", key);
                    stopServer(key);
                    startServer(newConfig);
                }
            } else {
                startServer(newConfig);
            }
        }
    }

    /**
     * Creates and starts a new proxy server instance.
     * 
     * @param config The configuration for the new server.
     */
    private void startServer(ProxyServerConfig config) {
        try {
            ProxyServer server = factory.create(config);
            String key = config.getName() != null ? config.getName() : String.valueOf(config.getPort());
            executor.submit(server::start);
            // Wait for the server socket to bind before registering as active.
            // If bind fails within 2 s, don't add to activeServers to avoid reporting
            // a server as running when it is actually not listening.
            if (server instanceof AbstractProxyServer abs && abs.awaitBind(2, TimeUnit.SECONDS)) {
                activeServers.put(key, server);
                log.info("Started {} proxy on port {}", config.getType(), config.getPort());
            } else {
                log.error("Proxy {} failed to bind on port {} within 2 s", config.getName(), config.getPort());
            }
        } catch (ProxyException e) {
            log.error("Failed to start proxy {}: {}", config.getName(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error starting proxy {}: {}", config.getName(), e.getMessage(), e);
        }
    }

    /**
     * Stops a specific proxy server.
     * 
     * @param key The unique key of the server to stop.
     */
    private void stopServer(String key) {
        ProxyServer server = activeServers.remove(key);
        if (server != null) {
            log.info("Stopping proxy {}...", key);
            try {
                server.stop();
            } catch (Exception e) {
                log.error("Error stopping server {}: {}", key, e.getMessage());
            }
        }
    }

    /**
     * Stops all active proxy servers and shuts down the internal executor.
     */
    public void stopAll() {
        log.info("Stopping all proxy servers...");
        for (String key : new ArrayList<>(activeServers.keySet())) {
            stopServer(key);
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("ProxyManager executor did not terminate cleanly after 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
