package com.yuubin.proxy;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyManager;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class ProxyBlacklistTest {

    private ProxyManager manager;
    private LoggingService logSrv;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() {
        if (manager != null)
            manager.stopAll();
        if (logSrv != null)
            logSrv.shutdown();
    }

    @Test
    void connection_rejected_when_ip_globally_blacklisted() throws Exception {
        YuubinProperties props = new YuubinProperties();
        props.setGlobalBlacklist(List.of("127.0.0.1"));

        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("p1");
        cfg.setPort(port);
        cfg.setType("HTTP");
        props.setProxies(List.of(cfg));

        startManager(props);

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            assertThat(s.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test
    void connection_rejected_when_ip_locally_blacklisted() throws Exception {
        YuubinProperties props = new YuubinProperties();

        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("p1");
        cfg.setPort(port);
        cfg.setType("HTTP");
        cfg.setBlacklist(List.of("127.0.0.1"));
        props.setProxies(List.of(cfg));

        startManager(props);

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            assertThat(s.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test
    void connection_allowed_when_ip_not_blacklisted() throws Exception {
        YuubinProperties props = new YuubinProperties();
        props.setGlobalBlacklist(List.of("1.1.1.1"));

        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setName("p1");
        cfg.setPort(port);
        cfg.setType("HTTP");
        cfg.setBlacklist(List.of("8.8.8.8"));
        props.setProxies(List.of(cfg));

        startManager(props);

        try (Socket s = new Socket("localhost", port)) {
            assertThat(s.isConnected()).isTrue();
            // Connection should stay open (at least for a bit)
            assertThat(s.isClosed()).isFalse();
        }
    }

    private void startManager(YuubinProperties props) {
        AuthService auth = new AuthService(props);
        this.logSrv = new LoggingService(props);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logSrv, new SimpleMeterRegistry(), props);
        manager = new ProxyManager(props, factory);
        manager.startServers();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket s = new Socket("localhost", port)) {
                return s.isConnected();
            } catch (java.io.IOException e) {
                return false;
            }
        });
    }
}
