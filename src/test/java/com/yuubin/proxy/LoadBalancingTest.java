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
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class LoadBalancingTest {

    private static ProxyManager manager;
    private static WireMockServer backend1;
    private static WireMockServer backend2;
    private static MetricsService metrics;
    private static LoggingService logs;
    private static int proxyPort;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            proxyPort = s.getLocalPort();
        }

        backend1 = new WireMockServer(wireMockConfig().dynamicPort());
        backend1.start();
        backend1.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("B1\n")));

        backend2 = new WireMockServer(wireMockConfig().dynamicPort());
        backend2.start();
        backend2.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("B2\n")));

        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setEnabled(false);
        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("load-balancer");
        cfg.setPort(proxyPort);
        cfg.setType("HTTP");

        Rule r = new Rule();
        r.setPath("/");
        r.setTargets(List.of(
                "http://localhost:" + backend1.port(),
                "http://localhost:" + backend2.port()));

        cfg.setRules(List.of(r));
        props.setProxies(List.of(cfg));

        AuthService auth = new AuthService(props);
        logs = new LoggingService(props);
        metrics = new MetricsService(props);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logs, metrics.getRegistry(), props);
        manager = new ProxyManager(props, factory);
        manager.startServers();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket s = new Socket("localhost", proxyPort)) {
                return s.isConnected();
            } catch (java.io.IOException e) {
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
    void testRoundRobin() throws Exception {
        String resp1 = sendRequest();
        String resp2 = sendRequest();
        String resp3 = sendRequest();
        String resp4 = sendRequest();

        // Should alternate between B1 and B2
        assertThat(resp1).isNotEqualTo(resp2);
        assertThat(resp1).isEqualTo(resp3);
        assertThat(resp2).isEqualTo(resp4);

        assertThat(List.of(resp1, resp2)).containsExactlyInAnyOrder("B1", "B2");
    }

    private String sendRequest() throws Exception {
        try (Socket s = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("GET / HTTP/1.1");
            out.println("Host: localhost");
            out.println();

            String line = in.readLine();
            while (!(line = in.readLine()).isEmpty())
                ;
            return in.readLine();
        }
    }
}
