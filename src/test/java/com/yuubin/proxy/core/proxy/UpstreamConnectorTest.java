package com.yuubin.proxy.core.proxy;

import com.yuubin.proxy.config.UpstreamProxyConfig;
import com.yuubin.proxy.core.exceptions.ConfigException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamConnectorTest {

    private ServerSocket server;
    private int port;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        server = new ServerSocket(0);
        port = server.getLocalPort();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.close();
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void testHttpConnectWithoutAuth() throws Exception {
        executor.submit(() -> {
            try (Socket s = server.accept();
                 InputStream in = s.getInputStream();
                 OutputStream out = s.getOutputStream()) {
                byte[] buffer = new byte[1024];
                int read = in.read(buffer);
                String request = new String(buffer, 0, read, StandardCharsets.US_ASCII);
                assertThat(request).contains("CONNECT target:80 HTTP/1.1");
                
                out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        UpstreamProxyConfig config = new UpstreamProxyConfig();
        config.setHost("localhost");
        config.setPort(port);
        config.setType("HTTP");

        try (Socket s = UpstreamConnector.connect("target", 80, config, 1000)) {
            assertThat(s.isConnected()).isTrue();
        }
    }

    @Test
    void testHttpConnectWithAuth() throws Exception {
        executor.submit(() -> {
            try (Socket s = server.accept();
                 InputStream in = s.getInputStream();
                 OutputStream out = s.getOutputStream()) {
                byte[] buffer = new byte[1024];
                int read = in.read(buffer);
                String request = new String(buffer, 0, read, StandardCharsets.US_ASCII);
                assertThat(request).contains("Proxy-Authorization: Basic " + 
                    Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8)));
                
                out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        UpstreamProxyConfig config = new UpstreamProxyConfig();
        config.setHost("localhost");
        config.setPort(port);
        config.setType("HTTP");
        config.setUsername("user");
        config.setPassword("pass");

        try (Socket s = UpstreamConnector.connect("target", 80, config, 1000)) {
            assertThat(s.isConnected()).isTrue();
        }
    }

    @Test
    void testHttpConnectError() {
        executor.submit(() -> {
            try (Socket s = server.accept();
                 InputStream in = s.getInputStream();
                 OutputStream out = s.getOutputStream()) {
                out.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        UpstreamProxyConfig config = new UpstreamProxyConfig();
        config.setHost("localhost");
        config.setPort(port);
        config.setType("HTTP");

        assertThatThrownBy(() -> UpstreamConnector.connect("target", 80, config, 1000))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("407");
    }

    @Test
    void testSocks5Connect() throws Exception {
        executor.submit(() -> {
            try (Socket s = server.accept();
                 DataInputStream in = new DataInputStream(s.getInputStream());
                 DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
                
                // Handshake
                assertThat(in.readByte()).isEqualTo((byte)5);
                assertThat(in.readByte()).isEqualTo((byte)1);
                assertThat(in.readByte()).isEqualTo((byte)0);
                
                out.writeByte(5);
                out.writeByte(0);
                out.flush();

                // Connect
                assertThat(in.readByte()).isEqualTo((byte)5);
                assertThat(in.readByte()).isEqualTo((byte)1);
                assertThat(in.readByte()).isEqualTo((byte)0);
                assertThat(in.readByte()).isEqualTo((byte)3); // Domain
                int hostLen = in.readByte();
                byte[] host = new byte[hostLen];
                in.readFully(host);
                assertThat(new String(host, StandardCharsets.UTF_8)).isEqualTo("target");
                assertThat(in.readShort()).isEqualTo((short)80);

                out.writeByte(5);
                out.writeByte(0);
                out.writeByte(0);
                out.writeByte(1); // IPv4
                out.write(new byte[]{127, 0, 0, 1});
                out.writeShort(1234);
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        UpstreamProxyConfig config = new UpstreamProxyConfig();
        config.setHost("localhost");
        config.setPort(port);
        config.setType("SOCKS5");

        try (Socket s = UpstreamConnector.connect("target", 80, config, 1000)) {
            assertThat(s.isConnected()).isTrue();
        }
    }

    @Test
    void testSocks5AuthNotSupported() {
        UpstreamProxyConfig config = new UpstreamProxyConfig();
        config.setHost("localhost");
        config.setPort(1234);
        config.setType("SOCKS5");
        config.setUsername("user");
        config.setPassword("pass");

        assertThatThrownBy(() -> UpstreamConnector.connect("target", 80, config, 1000))
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("authentication is not supported");
    }
}
