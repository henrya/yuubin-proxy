package com.yuubin.proxy.core.services;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import com.yuubin.proxy.config.AdminConfig;
import com.yuubin.proxy.config.YuubinProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service providing application metrics via Micrometer and a simple HTTP admin
 * server.
 */
public class MetricsService {
    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    private final PrometheusMeterRegistry registry;
    private HttpServer adminServer;
    private AdminConfig config;

    public MetricsService(YuubinProperties properties) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.config = properties.getAdmin();
        setupAdminServer();
    }

    private void setupAdminServer() {
        if (!config.isEnabled()) {
            return;
        }

        try {
            this.adminServer = HttpServer.create(new InetSocketAddress(config.getBindAddress(), config.getPort()), 0);

            // Health check endpoint
            adminServer.createContext("/health", exchange -> {
                byte[] response = "OK".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            });

            // Metrics endpoint (Prometheus format)
            adminServer.createContext("/metrics", exchange -> {
                String response = registry.scrape();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });

            adminServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            adminServer.start();
            log.info("Admin server started on port {} (/health, /metrics)", config.getPort());
        } catch (IOException e) {
            log.error("Failed to start admin server: {}", e.getMessage());
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public MeterRegistry getRegistry() {
        return registry;
    }

    public void updateProperties(YuubinProperties properties) {
        AdminConfig newConfig = properties.getAdmin();
        if (newConfig.isEnabled() != config.isEnabled() || newConfig.getPort() != config.getPort()
                || !java.util.Objects.equals(newConfig.getBindAddress(), config.getBindAddress())) {
            shutdown();
            this.config = newConfig;
            setupAdminServer();
        }
    }

    public void shutdown() {
        if (adminServer != null) {
            log.info("Stopping admin server...");
            adminServer.stop(0);
            adminServer = null;
        }
    }
}
