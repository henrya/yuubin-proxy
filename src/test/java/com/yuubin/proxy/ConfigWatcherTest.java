package com.yuubin.proxy;

import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.awaitility.Awaitility.await;
import java.time.Duration;

class ConfigWatcherTest {

  @Test
  void testFileWatcherReloadsConfig() throws Exception {
    Path configFile = Files.createTempFile("yuubin-watch", ".yml");
    int port1, port2;
    try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
      port1 = s1.getLocalPort();
      port2 = s2.getLocalPort();
    }

    // Initial config with port1
    String yaml1 = """
        admin:
          enabled: false
        proxies:
          - name: p1
            port: %d
            type: HTTP
        """.formatted(port1);
    Files.writeString(configFile, yaml1);

    YuubinProxyApplication app = new YuubinProxyApplication();
    picocli.CommandLine cmd = new picocli.CommandLine(app);

    Thread t = new Thread(() -> cmd.execute("-c", configFile.toAbsolutePath().toString()));
    t.setDaemon(true);
    t.start();

    await().atMost(Duration.ofSeconds(30)).until(() -> {
      try (java.net.Socket s = new java.net.Socket("localhost", port1)) {
        return s.isConnected();
      } catch (java.io.IOException e) {
        return false;
      }
    });

    // Update config to port2
    String yaml2 = """
        admin:
          enabled: false
        proxies:
          - name: p1
            port: %d
            type: HTTP
        """.formatted(port2);
    Files.writeString(configFile, yaml2);

    await().atMost(Duration.ofSeconds(30)).until(() -> {
      try (java.net.Socket s = new java.net.Socket("localhost", port2)) {
        return s.isConnected();
      } catch (java.io.IOException e) {
        return false;
      }
    });

    // Verify port1 is inactive
    await().atMost(Duration.ofSeconds(30)).until(() -> {
      try (java.net.Socket s = new java.net.Socket()) {
        s.connect(new java.net.InetSocketAddress("localhost", port1), 500);
        return false;
      } catch (java.io.IOException e) {
        return true;
      }
    });

    // Cleanup
    app.stop();
    t.join(5000);
    Files.deleteIfExists(configFile);
  }
}
