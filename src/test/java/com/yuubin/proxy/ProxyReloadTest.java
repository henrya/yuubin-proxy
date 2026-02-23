package com.yuubin.proxy;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyManager;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.services.MetricsService;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;

import static org.awaitility.Awaitility.await;
import java.time.Duration;

class ProxyReloadTest {

    @Test
    void testGracefulReload() throws Exception {
        int port1, port2;
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            port1 = s1.getLocalPort();
            port2 = s2.getLocalPort();
        }

        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setEnabled(false);
        ProxyServerConfig c1 = new ProxyServerConfig();
        c1.setName("p1");
        c1.setPort(port1);
        c1.setType("HTTP");
        props.setProxies(List.of(c1));

        AuthService auth = new AuthService(props);
        LoggingService logSrv = new LoggingService(props);
        MetricsService metrics = new MetricsService(props);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logSrv, metrics.getRegistry(), props);
        ProxyManager manager = new ProxyManager(props, factory);

        manager.startServers();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (java.net.Socket s = new java.net.Socket("localhost", port1)) {
                return s.isConnected();
            } catch (java.io.IOException e) {
                return false;
            }
        });

        // Reload with p2 and without p1
        ProxyServerConfig c2 = new ProxyServerConfig();
        c2.setName("p2");
        c2.setPort(port2);
        c2.setType("HTTP");

        manager.refreshServers(List.of(c2));
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (java.net.Socket s = new java.net.Socket("localhost", port2)) {
                return s.isConnected();
            } catch (java.io.IOException e) {
                return false;
            }
        });

        // Verify p1 is NOT running (connection refused)
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("localhost", port1), 500);
                return false;
            } catch (java.io.IOException e) {
                return true;
            }
        });

        manager.stopAll();
        metrics.shutdown();
        logSrv.shutdown();
    }
}
