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

class HostBasedRoutingTest {

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
        backend1.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("BACKEND 1\n")));

        backend2 = new WireMockServer(wireMockConfig().dynamicPort());
        backend2.start();
        backend2.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("BACKEND 2\n")));

        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setEnabled(false);
        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("host-routing");
        cfg.setPort(proxyPort);
        cfg.setType("HTTP");

        Rule r1 = new Rule();
        r1.setHost("abc.com");
        r1.setPath("/");
        r1.setTarget("http://localhost:" + backend1.port());
        r1.setReverse(true); // Enable Location rewriting

        Rule r2 = new Rule();
        r2.setHost("xyz.com");
        r2.setPath("/");
        r2.setTarget("http://localhost:" + backend2.port());

        cfg.setRules(List.of(r1, r2));
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
    void testHostRouting() throws Exception {
        // Request for abc.com
        try (Socket s = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("GET / HTTP/1.1");
            out.println("Host: abc.com");
            out.println();

            String line = in.readLine();
            assertThat(line).contains("200");
            while (!(line = in.readLine()).isEmpty())
                ;
            assertThat(in.readLine()).isEqualTo("BACKEND 1");
        }

        // Request for xyz.com
        try (Socket s = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("GET / HTTP/1.1");
            out.println("Host: xyz.com");
            out.println();

            String line = in.readLine();
            assertThat(line).contains("200");
            while (!(line = in.readLine()).isEmpty())
                ;
            assertThat(in.readLine()).isEqualTo("BACKEND 2");
        }
    }

    @Test
    void testXForwardedHeaders() throws Exception {
        try (Socket s = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("GET / HTTP/1.1");
            out.println("Host: abc.com");
            out.println();

            in.readLine(); // status
            while (!in.readLine().isEmpty())
                ; // skip headers
            in.readLine(); // body
        }

        backend1.verify(getRequestedFor(urlEqualTo("/"))
                .withHeader("X-Forwarded-For", matching(".+"))
                .withHeader("X-Forwarded-Proto", equalTo("http"))
                .withHeader("X-Forwarded-Host", equalTo("abc.com")));
    }

    @Test
    void testLocationRewriting() throws Exception {
        backend1.stubFor(get(urlEqualTo("/redirect"))
                .willReturn(aResponse().withStatus(302).withHeader("Location",
                        "http://localhost:" + backend1.port() + "/login")));

        try (Socket s = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("GET /redirect HTTP/1.1");
            out.println("Host: abc.com");
            out.println();

            String line = in.readLine();
            assertThat(line).contains("302");

            boolean foundLocation = false;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("location:")) {
                    assertThat(line).contains("abc.com");
                    assertThat(line).contains("/login");
                    assertThat(line).doesNotContain(String.valueOf(backend1.port()));
                    foundLocation = true;
                }
            }
            assertThat(foundLocation).isTrue();
        }
    }
}
