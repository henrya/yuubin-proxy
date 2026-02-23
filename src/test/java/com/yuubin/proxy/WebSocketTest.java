package com.yuubin.proxy;

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

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class WebSocketTest {

    private static ProxyManager manager;
    private static MetricsService metrics;
    private static LoggingService logs;
    private static int proxyPort;
    private static int wsBackendPort;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
            wsBackendPort = s2.getLocalPort();
        }

        // Dummy WebSocket Server (Echo)
        Thread wsServerThread = new Thread(() -> {
            try (ServerSocket srv = new ServerSocket(wsBackendPort)) {
                while (!srv.isClosed()) {
                    try (Socket client = srv.accept();
                            InputStream in = client.getInputStream();
                            OutputStream out = client.getOutputStream()) {

                        // Simple HTTP request reading
                        StringBuilder sb = new StringBuilder();
                        int c;
                        while ((c = in.read()) != -1) {
                            sb.append((char) c);
                            if (sb.toString().endsWith("\r\n\r\n"))
                                break;
                        }

                        // Send 101 Response
                        out.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.flush();

                        // Echo data
                        byte[] buffer = new byte[1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            out.flush();
                        }
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        });
        wsServerThread.setDaemon(true);
        wsServerThread.start();

        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setEnabled(false);
        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("ws-test");
        cfg.setPort(proxyPort);
        cfg.setType("HTTP");

        Rule r = new Rule();
        r.setPath("/ws");
        r.setTarget("http://localhost:" + wsBackendPort);
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
    }

    @Test
    void testWebSocketTunneling() throws Exception {
        try (Socket s = new Socket("localhost", proxyPort);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream()) {

            // Send Handshake
            String handshake = "GET /ws HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n\r\n";
            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read Handshake Response
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String line = reader.readLine();
            assertThat(line).contains("101 Switching Protocols");
            while (!(line = reader.readLine()).isEmpty())
                ;

            // Send Application Data (Raw bytes)
            out.write("Ping".getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Read Echoed Data with Awaitility
            byte[] response = new byte[4];
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                if (in.available() >= 4) {
                    int read = in.read(response);
                    return read == 4;
                }
                return false;
            });
            assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo("Ping");
        }
    }
}
