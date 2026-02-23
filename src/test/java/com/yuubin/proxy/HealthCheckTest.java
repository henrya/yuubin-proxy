package com.yuubin.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyManager;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.services.MetricsService;
import com.yuubin.proxy.entity.Rule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HealthCheckTest {

    private static ProxyManager manager;
    private static WireMockServer backend1;
    private static WireMockServer backend2;
    private static MetricsService metrics;
    private static LoggingService logs;
    private static int proxyPort;
    private static int b1Port;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            proxyPort = s.getLocalPort();
        }

        backend1 = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        backend1.start();
        b1Port = backend1.port();
        backend1.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200).withBody("OK")));
        backend1.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("B1\n")));

        backend2 = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        backend2.start();
        backend2.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200).withBody("OK")));
        backend2.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("B2\n")));

        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setEnabled(false);
        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("health-check-test");
        cfg.setPort(proxyPort);
        cfg.setType("HTTP");

        Rule r = new Rule();
        r.setPath("/");
        r.setTargets(List.of(
                "http://127.0.0.1:" + b1Port,
                "http://127.0.0.1:" + backend2.port()));
        r.setHealthCheckPath("/health");
        r.setHealthCheckInterval(2000); // 2s interval
        r.setHealthCheckTimeout(5000); // 5s timeout

        cfg.setRules(List.of(r));
        props.setProxies(List.of(cfg));

        AuthService auth = new AuthService(props);
        logs = new LoggingService(props);
        metrics = new MetricsService(props);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logs, metrics.getRegistry(), props);
        manager = new ProxyManager(props, factory);
        manager.startServers();

        // Wait for health checks to mark both as healthy
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            backend1.verify(getRequestedFor(urlEqualTo("/health")));
            backend2.verify(getRequestedFor(urlEqualTo("/health")));
        });

        java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            try {
                String resp = sendRequest();
                if (resp != null)
                    seen.add(resp);
                return seen.contains("B1") && seen.contains("B2");
            } catch (Exception e) {
                return false;
            }
        });
    }

    @AfterAll
    static void tearDown() {
        if (manager != null)
            manager.stopAll();
        if (metrics != null)
            metrics.shutdown();
        if (logs != null)
            logs.shutdown();
        if (backend1 != null)
            backend1.stop();
        if (backend2 != null)
            backend2.stop();
    }

    @Test
    void testFailover() throws Exception {
        // Stop backend 1
        backend1.stop();

        // Wait for failover
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String resp = sendRequest();
            assertThat(resp).isEqualTo("B2");
        });

        // Ensure stability - retry if background health check briefly marks B2
        // unhealthy
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (int i = 0; i < 5; i++) {
                assertThat(sendRequest()).isEqualTo("B2");
            }
        });

        // Restart backend 1
        backend1 = new WireMockServer(wireMockConfig().port(b1Port).bindAddress("127.0.0.1"));
        backend1.start();
        backend1.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200).withBody("OK")));
        backend1.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("B1\n")));

        // Wait for recovery
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String resp = sendRequest();
            assertThat(resp).isEqualTo("B1");
        });
    }

    private static String sendRequest() throws Exception {
        try (Socket s = new Socket("127.0.0.1", proxyPort);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("GET / HTTP/1.1");
            out.println("Host: 127.0.0.1");
            out.println();

            String statusLine = in.readLine();
            if (statusLine == null || !statusLine.contains("200"))
                return null;

            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // consume headers
            }
            String body = in.readLine();
            return body != null ? body.trim() : null;
        }
    }
}
