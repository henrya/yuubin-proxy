package com.yuubin.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.UpstreamProxyConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyManager;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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

class ProxyChainingTest {

    private static ProxyManager manager;
    private static WireMockServer wireMock;
    private static LoggingService logSrv;
    private static int upstreamPort;
    private static int downstreamPort;
    private static int socksUpstreamPort;
    private static int socksDownstreamPort;
    private static int targetPort;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket s1 = new ServerSocket(0);
                ServerSocket s2 = new ServerSocket(0);
                ServerSocket s3 = new ServerSocket(0);
                ServerSocket s4 = new ServerSocket(0)) {
            upstreamPort = s1.getLocalPort();
            downstreamPort = s2.getLocalPort();
            socksUpstreamPort = s3.getLocalPort();
            socksDownstreamPort = s4.getLocalPort();
        }

        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        targetPort = wireMock.port();

        wireMock.stubFor(get(urlEqualTo("/chained"))
                .willReturn(aResponse().withStatus(200).withBody("CHAINED OK")));

        YuubinProperties props = new YuubinProperties();

        // 1. HTTP Chaining
        ProxyServerConfig upstream = new ProxyServerConfig();
        upstream.setName("upstream");
        upstream.setPort(upstreamPort);
        upstream.setType("HTTP");

        ProxyServerConfig downstream = new ProxyServerConfig();
        downstream.setName("downstream");
        downstream.setPort(downstreamPort);
        downstream.setType("HTTP");

        UpstreamProxyConfig chain = new UpstreamProxyConfig();
        chain.setHost("localhost");
        chain.setPort(upstreamPort);
        chain.setType("HTTP");
        downstream.setUpstreamProxy(chain);

        // 2. SOCKS5 Chaining
        ProxyServerConfig socksUpstream = new ProxyServerConfig();
        socksUpstream.setName("socksUpstream");
        socksUpstream.setPort(socksUpstreamPort);
        socksUpstream.setType("SOCKS5");

        ProxyServerConfig socksDownstream = new ProxyServerConfig();
        socksDownstream.setName("socksDownstream");
        socksDownstream.setPort(socksDownstreamPort);
        socksDownstream.setType("SOCKS5");

        UpstreamProxyConfig socksChain = new UpstreamProxyConfig();
        socksChain.setHost("localhost");
        socksChain.setPort(socksUpstreamPort);
        socksChain.setType("SOCKS5");
        socksDownstream.setUpstreamProxy(socksChain);

        props.setProxies(List.of(upstream, downstream, socksUpstream, socksDownstream));

        AuthService auth = new AuthService(props);
        logSrv = new LoggingService(props);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logSrv, new SimpleMeterRegistry(), props);
        manager = new ProxyManager(props, factory);
        manager.startServers();

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            boolean[] active = new boolean[4];
            int[] ports = { upstreamPort, downstreamPort, socksUpstreamPort, socksDownstreamPort };
            for (int i = 0; i < 4; i++) {
                try (Socket s = new Socket("localhost", ports[i])) {
                    active[i] = s.isConnected();
                } catch (java.io.IOException e) {
                    active[i] = false;
                }
            }
            return active[0] && active[1] && active[2] && active[3];
        });
    }

    @AfterAll
    static void tearDown() {
        if (manager != null)
            manager.stopAll();
        if (logSrv != null)
            logSrv.shutdown();
        if (wireMock != null)
            wireMock.stop();
    }

    @Test
    void testHttpChaining() throws Exception {
        try (Socket s = new Socket("localhost", downstreamPort);
                PrintWriter pw = new PrintWriter(s.getOutputStream(), true);
                BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {

            pw.println("GET http://localhost:" + targetPort + "/chained HTTP/1.1");
            pw.println("Host: localhost");
            pw.println();

            String line = br.readLine();
            assertThat(line).contains("200");

            while ((line = br.readLine()) != null && !line.isEmpty()) {
                // skip headers
            }

            char[] buffer = new char[1024];
            int read = br.read(buffer);
            String body = new String(buffer, 0, read);
            assertThat(body).contains("CHAINED OK");
        }
    }

    @Test
    void testSocks5Chaining() throws Exception {
        try (Socket s = new Socket("localhost", socksDownstreamPort);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream())) {

            // Handshake with downstream
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 0);

            // Connect to target through downstream (which chains to upstream)
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            out.writeByte(1); // IPv4
            out.write(new byte[] { 127, 0, 0, 1 });
            out.writeShort(targetPort);
            out.flush();

            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 0); // Success

            // Consume remaining SOCKS5 response bytes
            in.readByte();
            in.readByte();
            in.readFully(new byte[4]);
            in.readShort();

            // Should be connected to target now
            PrintWriter pw = new PrintWriter(s.getOutputStream(), true);
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
            pw.println("GET /chained HTTP/1.1");
            pw.println("Host: localhost");
            pw.println();

            assertThat(br.readLine()).contains("200");
        }
    }
}
