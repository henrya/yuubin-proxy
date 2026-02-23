package com.yuubin.proxy;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class YuubinProxyApplicationTest {

    @Test
    void main_withHelpOption_returnsZero() {
        int exitCode = new CommandLine(new YuubinProxyApplication()).execute("--help");
        assertThat(exitCode).isZero();
    }

    @Test
    void main_withVersionOption_returnsZero() {
        int exitCode = new CommandLine(new YuubinProxyApplication()).execute("--version");
        assertThat(exitCode).isZero();
    }

    @Test
    void call_withValidConfig_startsServers() throws Exception {
        Path configFile = Files.createTempFile("yuubin", ".yml");
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        String yaml = "admin:\n" +
                "  enabled: false\n" +
                "proxies:\n" +
                "  - name: test\n" +
                "    port: " + port + "\n" +
                "    type: HTTP\n" +
                "auth:\n" +
                "  enabled: false\n" +
                "logging:\n" +
                "  format: '%h %r'\n";
        Files.writeString(configFile, yaml);

        System.setProperty("picocli.ansi", "false"); // for tests
        YuubinProxyApplication app = new YuubinProxyApplication();
        CommandLine cmd = new CommandLine(app);

        Thread appThread = new Thread(() -> cmd.execute("-c", configFile.toAbsolutePath().toString()));
        appThread.setDaemon(true);
        appThread.start();

        // Wait for the application to start and the proxy port to be open
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (java.net.Socket s = new java.net.Socket("localhost", port)) {
                return s.isConnected();
            } catch (java.io.IOException e) {
                return false;
            }
        });

        // Stop the application instance
        app.stop();

        // Wait for the application thread to terminate
        await().atMost(Duration.ofSeconds(10)).until(() -> !appThread.isAlive());

        Files.deleteIfExists(configFile);
    }

    @Test
    void call_withInvalidConfig_returnsError() throws Exception {
        Path configFile = Files.createTempFile("yuubin-bad", ".yml");
        Files.writeString(configFile, "invalid yaml content: !!!");

        YuubinProxyApplication app = new YuubinProxyApplication();
        int exitCode = new CommandLine(app).execute("-c", configFile.toAbsolutePath().toString());

        assertThat(exitCode).isEqualTo(1);
        Files.deleteIfExists(configFile);
    }
}
