package com.yuubin.proxy.core.proxy.impl.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.entity.Rule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.*;
import java.net.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class HttpProxyServerTest {

    private HttpProxyServer proxyServer;
    private WireMockServer wireMockServer;
    private AuthService authService;
    private LoggingService loggingService;
    private MeterRegistry registry;
    private YuubinProperties globalProps;
    private ProxyServerConfig config;
    private int proxyPort;
    private int targetPort;

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket s1 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
        }

        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        targetPort = wireMockServer.port();
        WireMock.configureFor("localhost", targetPort);

        authService = Mockito.mock(AuthService.class);
        loggingService = Mockito.mock(LoggingService.class);
        registry = new SimpleMeterRegistry();
        globalProps = new YuubinProperties();
        Mockito.when(authService.authenticate(Mockito.anyString())).thenReturn(true);

        config = new ProxyServerConfig();
        config.setPort(proxyPort);
        config.setType("HTTP");

        proxyServer = new HttpProxyServer(config, authService, loggingService, registry, globalProps);
        Thread t = new Thread(proxyServer::start);
        t.setDaemon(true);
        t.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket s = new Socket("localhost", proxyPort)) {
                return s.isConnected();
            } catch (IOException e) {
                return false;
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (proxyServer != null)
            proxyServer.stop();
        if (wireMockServer != null)
            wireMockServer.stop();
    }

    @Test
    void proxy_handlesRegularHttp() throws Exception {
        stubFor(get(urlEqualTo("/test")).willReturn(aResponse().withStatus(200).withBody("OK")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try (Socket socket = new Socket("localhost", proxyPort);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println("GET http://localhost:" + targetPort + "/test HTTP/1.1");
                out.println("Host: localhost");
                out.println();

                assertThat(in.readLine()).contains("200");
            }
        });
    }

    @Test
    void proxy_handlesConnect() throws Exception {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try (Socket socket = new Socket("localhost", proxyPort);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                out.println("CONNECT localhost:" + targetPort + " HTTP/1.1");
                out.println();

                assertThat(in.readLine()).contains("200 Connection Established");
            }
        });
    }

    @Test
    void proxy_deniesUnauthenticatedRequest() throws Exception {
        ProxyServerConfig cfg = new ProxyServerConfig();
        int p;
        try (ServerSocket s = new ServerSocket(0)) {
            p = s.getLocalPort();
        }
        cfg.setPort(p);
        cfg.setType("HTTP");
        cfg.setAuthEnabled(true);

        Mockito.when(authService.authenticate(Mockito.any())).thenReturn(false);

        HttpProxyServer srv = new HttpProxyServer(cfg, authService, loggingService, registry, globalProps);
        Thread t = new Thread(srv::start);
        t.setDaemon(true);
        t.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket socket = new Socket("localhost", p)) {
                return socket.isConnected();
            } catch (IOException e) {
                return false;
            }
        });

        try (Socket socket = new Socket("localhost", p);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("GET / HTTP/1.1");
            out.println("Host: localhost");
            out.println();
            assertThat(in.readLine()).contains("407");
        } finally {
            srv.stop();
        }
    }

    @Test
    void proxy_handlesNoMatchingRule() throws Exception {
        Rule rule = new Rule();
        rule.setPath("/some-path");
        rule.setTarget("http://localhost:1");
        config.setRules(java.util.List.of(rule));

        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("GET /no-rule HTTP/1.1");
            out.println("Host: localhost");
            out.println();
            assertThat(in.readLine()).contains("404");
        }
    }

    @Test
    void proxy_handlesInvalidUri() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("GET [invalid] HTTP/1.1");
            out.println("Host: localhost");
            out.println();
            assertThat(in.readLine()).contains("400");
        }
    }

    @Test
    void proxy_handlesPostWithBody() throws Exception {
        stubFor(post(urlEqualTo("/post")).willReturn(aResponse().withStatus(201)));

        try (Socket socket = new Socket("localhost", proxyPort);
                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream()) {

            String req = "POST http://localhost:" + targetPort + "/post HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: 4\r\n\r\n" +
                    "DATA";
            os.write(req.getBytes());
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            assertThat(reader.readLine()).contains("201");

            await().atMost(Duration.ofSeconds(10)).untilAsserted(
                    () -> verify(postRequestedFor(urlEqualTo("/post")).withRequestBody(equalTo("DATA"))));
        }
    }

    @Test
    void proxy_handlesRedirects() throws Exception {
        config.setMaxRedirects(2);

        stubFor(get(urlEqualTo("/r1")).willReturn(aResponse().withStatus(302).withHeader("Location", "/r2")));
        stubFor(get(urlEqualTo("/r2")).willReturn(aResponse().withStatus(302).withHeader("Location", "/final")));
        stubFor(get(urlEqualTo("/final")).willReturn(aResponse().withStatus(200).withBody("FINAL")));

        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("GET http://localhost:" + targetPort + "/r1 HTTP/1.1");
            out.println("Host: localhost");
            out.println();

            String line = in.readLine();
            assertThat(line).contains("200");

            while ((line = in.readLine()) != null && !line.isEmpty())
                ;

            char[] buffer = new char[5];
            int read = in.read(buffer);
            assertThat(new String(buffer, 0, read)).isEqualTo("FINAL");
        }
    }

    @Test
    void proxy_stopsAtMaxRedirects() throws Exception {
        config.setMaxRedirects(1);

        stubFor(get(urlEqualTo("/r1")).willReturn(aResponse().withStatus(302).withHeader("Location", "/r2")));
        stubFor(get(urlEqualTo("/r2")).willReturn(aResponse().withStatus(302).withHeader("Location", "/r3")));

        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("GET http://localhost:" + targetPort + "/r1 HTTP/1.1");
            out.println("Host: localhost");
            out.println();

            String line = in.readLine();
            assertThat(line).contains("302"); // Should stop after 1st redirect and return the 2nd 302
        }
    }

    @Test
    void proxy_handlesLargeBody() throws Exception {
        stubFor(post(urlEqualTo("/large")).willReturn(aResponse().withStatus(200)));

        byte[] largeData = new byte[2 * 1024 * 1024]; // 2MB
        java.util.Arrays.fill(largeData, (byte) 'A');

        try (Socket socket = new Socket("localhost", proxyPort);
                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream()) {

            String head = "POST http://localhost:" + targetPort + "/large HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + largeData.length + "\r\n\r\n";
            os.write(head.getBytes());
            os.write(largeData);
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            assertThat(reader.readLine()).contains("200");
        }
    }

    @Test
    void proxy_handlesKeepAlive() throws Exception {
        stubFor(get(urlEqualTo("/k1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Length", "2").withBody("K1")));
        stubFor(get(urlEqualTo("/k2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Length", "2").withBody("K2")));

        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // 1st request
            out.println("GET http://localhost:" + targetPort + "/k1 HTTP/1.1");
            out.println("Host: localhost");
            out.println("Connection: keep-alive");
            out.println();

            String statusLine1 = reader.readLine();
            assertThat(statusLine1).contains("200");
            while (!reader.readLine().isEmpty())
                ; // Consume headers

            char[] body1 = new char[2];
            reader.read(body1);
            assertThat(new String(body1)).isEqualTo("K1");

            // 2nd request on same socket
            out.println("GET http://localhost:" + targetPort + "/k2 HTTP/1.1");
            out.println("Host: localhost");
            out.println();

            String statusLine2 = reader.readLine();
            assertThat(statusLine2).contains("200");
            while (!reader.readLine().isEmpty())
                ; // Consume headers

            char[] body2 = new char[2];
            reader.read(body2);
            assertThat(new String(body2)).isEqualTo("K2");
        }
    }

    @Test
    void proxy_handlesConnectFailure() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("CONNECT localhost:1 HTTP/1.1");
            out.println();
            assertThat(in.readLine()).contains("502");
        }
    }

    @Test
    void proxy_injectsCustomHeaders() throws Exception {
        Rule rule = new Rule();
        rule.setPath("/");
        rule.setTarget("http://localhost:" + targetPort);
        rule.setHeaders(java.util.Map.of("X-Custom-Header", "TestValue"));
        config.setRules(java.util.List.of(rule));

        stubFor(get(urlEqualTo("/custom-header"))
                .withHeader("X-Custom-Header", equalTo("TestValue"))
                .willReturn(aResponse().withStatus(200)));

        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("GET /custom-header HTTP/1.1");
            out.println("Host: localhost");
            out.println();
            String responseLine = in.readLine();
            if (!responseLine.contains("200")) {
                System.out.println("TEST FAILURE: Expected 200, got " + responseLine);
                String line;
                while ((line = in.readLine()) != null) {
                    System.out.println(line);
                }
            }
            assertThat(responseLine).contains("200");
        }
    }

    @Test
    void proxy_loadBalancesAcrossTargets() throws Exception {
        WireMockServer wireMock2 = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock2.start();
        int targetPort2 = wireMock2.port();

        try {
            stubFor(get(urlEqualTo("/lb"))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Length", "2").withBody("T1")));
            wireMock2.stubFor(get(urlEqualTo("/lb"))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Length", "2").withBody("T2")));

            Rule rule = new Rule();
            rule.setPath("/");
            rule.setTargets(java.util.List.of("http://localhost:" + targetPort, "http://localhost:" + targetPort2));
            config.setRules(java.util.List.of(rule));

            java.util.Set<String> bodies = new java.util.HashSet<>();
            for (int i = 0; i < 4; i++) {
                try (Socket socket = new Socket("localhost", proxyPort);
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    out.println("GET /lb HTTP/1.1");
                    out.println("Host: localhost:" + proxyPort);
                    out.println("Connection: close");
                    out.println();

                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty())
                        ;

                    String body = in.lines().collect(java.util.stream.Collectors.joining("\n")).trim();
                    bodies.add(body);
                }
            }
            assertThat(bodies).containsExactlyInAnyOrder("T1", "T2");
        } finally {
            wireMock2.stop();
        }
    }

    @Test
    void proxy_rewritesLocationHeaderInReverseMode() throws Exception {
        Rule rule = new Rule();
        rule.setPath("/");
        rule.setTarget("http://localhost:" + targetPort);
        rule.setReverse(true);
        config.setRules(java.util.List.of(rule));

        stubFor(get(urlEqualTo("/rev"))
                .willReturn(aResponse().withStatus(302).withHeader("Location",
                        "http://localhost:" + targetPort + "/redirected")));

        try (Socket socket = new Socket("localhost", proxyPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            out.println("GET /rev HTTP/1.1");
            out.println("Host: localhost:" + proxyPort);
            out.println("Connection: close");
            out.println();

            String line;
            boolean foundLocation = false;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("location:")) {
                    assertThat(line).contains("http://localhost:" + proxyPort + "/redirected");
                    foundLocation = true;
                }
            }
            assertThat(foundLocation).isTrue();
        }
    }
}
