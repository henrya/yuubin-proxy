package com.yuubin.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyManager;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.entity.Rule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for TLS-enabled HTTP proxy servers.
 * Validates the full chain: keystore loading → TLS server socket → HTTPS client
 * connection.
 */
class TlsProxyTest {

    @TempDir
    static Path tempDir;

    private static ProxyManager manager;
    private static WireMockServer wireMock;
    private static LoggingService logSrv;
    private static int tlsProxyPort;
    private static int targetPort;
    private static File keystoreFile;
    private static SSLSocketFactory clientSslFactory;
    private static final String KEYSTORE_PASSWORD = "testpassword";

    @BeforeAll
    static void setUp() throws Exception {
        // Allocate a free port for the TLS proxy
        try (ServerSocket s = new ServerSocket(0)) {
            tlsProxyPort = s.getLocalPort();
        }

        // Start WireMock as the backend
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        targetPort = wireMock.port();

        wireMock.stubFor(get(urlEqualTo("/tls-test"))
                .willReturn(aResponse().withStatus(200).withBody("TLS OK")));

        // Generate a self-signed PKCS12 keystore
        keystoreFile = tempDir.resolve("server.p12").toFile();
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-keystore", keystoreFile.getAbsolutePath(),
                "-storepass", KEYSTORE_PASSWORD,
                "-keypass", KEYSTORE_PASSWORD,
                "-dname", "CN=localhost",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-ext", "san=ip:127.0.0.1,dns:localhost");
        Process process = pb.start();
        try {
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            assertThat(finished).as("keytool should finish in 10s").isTrue();
            assertThat(process.exitValue()).as("keytool exit code").isEqualTo(0);
        } finally {
            process.destroy();
        }

        // Build a client SSLContext that trusts our self-signed keystore
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystoreFile)) {
            trustStore.load(fis, KEYSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(null, tmf.getTrustManagers(), null);
        clientSslFactory = sslContext.getSocketFactory();

        // Build proxy config
        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(tempDir.toString());

        ProxyServerConfig tlsConfig = new ProxyServerConfig();
        tlsConfig.setName("tls-test");
        tlsConfig.setPort(tlsProxyPort);
        tlsConfig.setType("HTTP");
        tlsConfig.setTlsEnabled(true);
        tlsConfig.setKeystorePath("server.p12");
        tlsConfig.setKeystorePassword(KEYSTORE_PASSWORD);

        Rule rule = new Rule();
        rule.setPath("/");
        rule.setTarget("http://localhost:" + targetPort);
        tlsConfig.setRules(List.of(rule));

        props.setProxies(List.of(tlsConfig));

        logSrv = new LoggingService(props);
        AuthService authSrv = new AuthService(props);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProxyServerFactory factory = new ProxyServerFactory(authSrv, logSrv, registry, props);

        manager = new ProxyManager(props, factory);
        manager.startServers();

        // Wait for the TLS proxy to be accepting connections
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    try (SSLSocket probe = (SSLSocket) clientSslFactory.createSocket("127.0.0.1", tlsProxyPort)) {
                        probe.startHandshake();
                        assertThat(probe.isConnected()).isTrue();
                    }
                });
    }

    @AfterAll
    static void tearDown() {
        if (manager != null)
            manager.stopAll();
        if (wireMock != null)
            wireMock.stop();
        if (logSrv != null)
            logSrv.shutdown();
    }

    /**
     * Gap #5 + #6: End-to-end TLS integration test.
     * Validates that AbstractProxyServer.initializeServerSocket creates a working
     * SSL server socket from the PKCS12 keystore, and that an HTTPS client can
     * connect, send a request, and receive a proxied response.
     */
    @Test
    void tlsProxy_acceptsHttpsConnections_andProxiesRequests() throws Exception {
        // Send a raw HTTP request over a TLS socket to the proxy
        try (SSLSocket socket = (SSLSocket) clientSslFactory.createSocket("127.0.0.1", tlsProxyPort)) {
            socket.startHandshake();
            socket.setSoTimeout(5000);

            // Send an HTTP request with absolute URI (proxy-style)
            String request = "GET http://localhost:" + targetPort + "/tls-test HTTP/1.1\r\n"
                    + "Host: localhost:" + targetPort + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read the response
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String statusLine = reader.readLine();
            assertThat(statusLine).isNotNull();
            assertThat(statusLine).contains("200");

            // Read until empty line (end of headers), then body
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Skip headers
            }
            String body = reader.readLine();
            assertThat(body).isEqualTo("TLS OK");
        }

        // Verify WireMock received the forwarded request
        wireMock.verify(getRequestedFor(urlEqualTo("/tls-test")));
    }

    /**
     * Verifies that the TLS proxy enforces TLS v1.3 Protocol.
     */
    @Test
    void tlsProxy_enforcesTlsV13() throws Exception {
        try (SSLSocket socket = (SSLSocket) clientSslFactory.createSocket("127.0.0.1", tlsProxyPort)) {
            socket.startHandshake();

            String protocol = socket.getSession().getProtocol();
            assertThat(protocol).isEqualTo("TLSv1.3");
        }
    }
}
