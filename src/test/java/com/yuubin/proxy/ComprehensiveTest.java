package com.yuubin.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.yuubin.proxy.config.AuthConfig;
import com.yuubin.proxy.config.LoggingConfig;
import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.entity.Rule;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyManager;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.services.MetricsService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that verifies all supported proxy protocols
 * (HTTP, SOCKS4, SOCKS5) against a live WireMock backend.
 */
class ComprehensiveTest {

    private static ProxyManager manager;
    private static WireMockServer wireMock;
    private static MetricsService metrics;
    private static LoggingService logSrv;
    private static int httpPort;
    private static int s4Port;
    private static int s5Port;
    private static int targetPort;

    /**
     * Sets up the test environment: finds free ports, starts WireMock,
     * and initializes the ProxyManager with all protocols.
     */
    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket s1 = new ServerSocket(0);
                ServerSocket s2 = new ServerSocket(0);
                ServerSocket s3 = new ServerSocket(0)) {
            httpPort = s1.getLocalPort();
            s4Port = s2.getLocalPort();
            s5Port = s3.getLocalPort();
        }

        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        targetPort = wireMock.port();

        wireMock.stubFor(get(urlEqualTo("/it"))
                .willReturn(aResponse().withStatus(200).withBody("IT OK")));

        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setEnabled(false);

        // Configure HTTP Proxy
        ProxyServerConfig h = new ProxyServerConfig();
        h.setName("h");
        h.setPort(httpPort);
        h.setType("HTTP");
        Rule r = new Rule();
        r.setPath("/");
        r.setTarget("http://localhost:" + targetPort);
        h.setRules(List.of(r));

        // Configure SOCKS4 Proxy
        ProxyServerConfig s4 = new ProxyServerConfig();
        s4.setName("s4");
        s4.setPort(s4Port);
        s4.setType("SOCKS4");

        // Configure SOCKS5 Proxy
        ProxyServerConfig s5 = new ProxyServerConfig();
        s5.setName("s5");
        s5.setPort(s5Port);
        s5.setType("SOCKS5");

        props.setProxies(List.of(h, s4, s5));
        props.setLogging(new LoggingConfig());
        props.setAuth(new AuthConfig());

        AuthService auth = new AuthService(props);
        logSrv = new LoggingService(props);
        metrics = new MetricsService(props);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logSrv, metrics.getRegistry(), props);
        manager = new ProxyManager(props, factory);
        manager.startServers();
        // Wait for all three proxy ports to be ready (robust on slow CI runners).
        awaitPort(httpPort);
        awaitPort(s4Port);
        awaitPort(s5Port);
    }

    /** Polls until the given port accepts a TCP connection or 5 s elapses. */
    private static void awaitPort(int port) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress("localhost", port), 200);
                        return true;
                    } catch (IOException e) {
                        return false;
                    }
                });
    }

    /**
     * Stops all servers and resources after test completion.
     */
    @AfterAll
    static void tearDown() {
        if (manager != null)
            manager.stopAll();
        if (metrics != null)
            metrics.shutdown();
        if (logSrv != null)
            logSrv.shutdown();
        if (wireMock != null)
            wireMock.stop();
    }

    /**
     * Verifies that each proxy type correctly forwards traffic to the backend.
     */
    @Test
    void testAllProxies() throws Exception {
        // Test HTTP Forwarding
        try (Socket s = new Socket("localhost", httpPort);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            out.println("GET /it HTTP/1.1");
            out.println("Host: localhost");
            out.println();
            assertThat(in.readLine()).contains("200");
        }

        // Test SOCKS4 Connectivity
        try (Socket s = new Socket("localhost", s4Port);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream())) {
            out.writeByte(4);
            out.writeByte(1);
            out.writeShort(targetPort);
            out.write(InetAddress.getByName("localhost").getAddress());
            out.writeByte(0);
            assertThat(in.readByte()).isEqualTo((byte) 0);
            assertThat(in.readByte()).isEqualTo((byte) 90);
        }

        // Test SOCKS5 full data relay — handshake + CONNECT + HTTP GET
        try (Socket s = new Socket("localhost", s5Port);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream())) {

            // Greeting: no-auth
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            assertThat(in.readByte()).isEqualTo((byte) 5); // VER
            assertThat(in.readByte()).isEqualTo((byte) 0); // METHOD = NO_AUTH

            // CONNECT to WireMock backend via domain name
            byte[] host = "localhost".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeByte(5); // VER
            out.writeByte(1); // CMD = CONNECT
            out.writeByte(0); // RSV
            out.writeByte(3); // ATYP = DOMAINNAME
            out.writeByte(host.length);
            out.write(host);
            out.writeShort(targetPort);

            // CONNECT response: VER=5, REP=0 (success), RSV=0, ATYP=1 (IPv4) + 4+2 bytes
            assertThat(in.readByte()).isEqualTo((byte) 5); // VER
            assertThat(in.readByte()).isEqualTo((byte) 0); // REP = success
            in.readByte(); // RSV
            int atyp = in.readByte();
            if (atyp == 1) {
                in.skipBytes(4 + 2);
            } // IPv4 + port
            else if (atyp == 3) {
                in.skipBytes(in.readByte() + 2);
            } // domain + port
            else {
                in.skipBytes(16 + 2);
            } // IPv6 + port

            // Send HTTP GET through the now-established tunnel
            out.write(("GET /it HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Read full response and verify the WireMock stub body is present
            java.io.ByteArrayOutputStream resp = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.getInputStream().read(buf)) >= 0) {
                resp.write(buf, 0, n);
            }
            String body = resp.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).contains("200").contains("IT OK");
        }
    }

    @Test
    void testBlacklist() throws Exception {
        // Find a free port
        int blacklistPort;
        try (ServerSocket s = new ServerSocket(0)) {
            blacklistPort = s.getLocalPort();
        }

        YuubinProperties props = new YuubinProperties();
        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("blacklist-test");
        cfg.setPort(blacklistPort);
        cfg.setType("HTTP");
        cfg.setBlacklist(List.of("127.0.0.1"));
        props.setProxies(List.of(cfg));

        AuthService auth = new AuthService(props);
        LoggingService logSrv = new LoggingService(props);
        MetricsService metrics = new MetricsService(props);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logSrv, metrics.getRegistry(), props);
        ProxyManager localManager = new ProxyManager(props, factory);
        localManager.startServers();
        awaitPort(blacklistPort);
        try {
            try (Socket s = new Socket()) {
                // Connection might be accepted but closed immediately, or rejected if OS/Java
                // handles it fast
                s.connect(new InetSocketAddress("127.0.0.1", blacklistPort), 500);
                // If connected, trying to read should return -1 (EOF)
                assertThat(s.getInputStream().read()).isEqualTo(-1);
            }
        } finally {
            localManager.stopAll();
            metrics.shutdown();
            logSrv.shutdown();
        }
    }
}
