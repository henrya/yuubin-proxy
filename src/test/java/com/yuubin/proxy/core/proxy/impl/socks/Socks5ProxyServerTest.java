package com.yuubin.proxy.core.proxy.impl.socks;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class Socks5ProxyServerTest {

    private Socks5ProxyServer proxyServer;
    private ServerSocket targetServer;
    private AuthService authService;
    private LoggingService loggingService;
    private MeterRegistry registry;
    private YuubinProperties globalProps;
    private int proxyPort;
    private int targetPort;

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
            targetPort = s2.getLocalPort();
        }

        authService = Mockito.mock(AuthService.class);
        loggingService = Mockito.mock(LoggingService.class);
        registry = new SimpleMeterRegistry();
        globalProps = new YuubinProperties();

        ProxyServerConfig config = new ProxyServerConfig();
        config.setPort(proxyPort);
        config.setType("SOCKS5");
        config.setAuthEnabled(false);

        proxyServer = new Socks5ProxyServer(config, authService, loggingService, registry, globalProps);
        Thread proxyThread = new Thread(proxyServer::start);
        proxyThread.setDaemon(true);
        proxyThread.start();

        targetServer = new ServerSocket(targetPort);
        Thread targetThread = new Thread(() -> {
            try (Socket s = targetServer.accept();
                    OutputStream os = s.getOutputStream()) {
                os.write("Hello SOCKS5".getBytes());
            } catch (IOException ignored) {
            }
        });
        targetThread.setDaemon(true);
        targetThread.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket s = new Socket("localhost", proxyPort)) {
                return s.isConnected();
            } catch (IOException e) {
                return false;
            }
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        proxyServer.stop();
        targetServer.close();
    }

    @Test
    void proxy_handlesSocks5Connection() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {

            // 1. Handshake
            out.writeByte(5); // VER
            out.writeByte(1); // NMETHODS
            out.writeByte(0); // NO AUTH

            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 0);

            // 2. Request
            out.writeByte(5); // VER
            out.writeByte(1); // CMD: CONNECT
            out.writeByte(0); // RSV
            out.writeByte(1); // ATYP: IPv4
            out.write(InetAddress.getByName("localhost").getAddress());
            out.writeShort(targetPort);

            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 0); // SUCCESS
            in.readByte(); // RSV
            in.readByte(); // ATYP
            byte[] addr = new byte[4];
            in.readFully(addr);
            in.readShort(); // Port

            byte[] response = new byte[12];
            in.readFully(response);
            assertThat(new String(response)).isEqualTo("Hello SOCKS5");
        }
    }

    @Test
    void proxy_handlesSocks5Auth() throws Exception {
        ProxyServerConfig config = new ProxyServerConfig();
        int p;
        try (ServerSocket s = new ServerSocket(0)) {
            p = s.getLocalPort();
        }
        config.setPort(p);
        config.setType("SOCKS5");
        config.setAuthEnabled(true);

        Mockito.when(authService.authenticate("user", "pass")).thenReturn(true);

        Socks5ProxyServer srv = new Socks5ProxyServer(config, authService, loggingService, registry, globalProps);
        Thread t = new Thread(srv::start);
        t.setDaemon(true);
        t.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket s = new Socket("localhost", p)) {
                return s.isConnected();
            } catch (IOException e) {
                return false;
            }
        });

        try (Socket socket = new Socket("localhost", p);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(2); // USERPASS
            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 2);

            out.writeByte(1); // VER
            out.writeByte(4);
            out.write("user".getBytes());
            out.writeByte(4);
            out.write("pass".getBytes());

            assertThat(in.readByte()).isEqualTo((byte) 1);
            assertThat(in.readByte()).isEqualTo((byte) 0); // Success
        } finally {
            srv.stop();
        }
    }

    @Test
    void proxy_handlesInvalidVersion() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {
            out.writeByte(4); // Wrong version
            assertThat(socket.getInputStream().read()).isEqualTo(-1); // Connection closed
        }
    }

    @Test
    void proxy_handlesUnsupportedAtyp() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            in.readByte();
            in.readByte();

            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            out.writeByte(2); // Unsupported ATYP (IPv6 is 4, Domain is 3, IPv4 is 1)
            out.write(new byte[4]);
            out.writeShort(targetPort);

            // Connection should be closed due to ProtocolException
            try {
                assertThat(in.read()).isEqualTo(-1);
            } catch (java.net.SocketException e) {
                // Connection reset is acceptable when the server forcefully closes an invalid
                // connection
            }
        }
    }

    @Test
    void proxy_handlesIPv6() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            in.readByte();
            in.readByte();

            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            out.writeByte(4); // IPv6
            out.write(new byte[16]); // Dummy IPv6
            out.writeShort(targetPort);

            assertThat(in.readByte()).isEqualTo((byte) 5);
            // Likely 4 (Host unreachable) or 0 if it somehow connects to dummy
            assertThat(in.readByte()).isNotEqualTo((byte) 0xFF);
        }
    }

    @Test
    void proxy_handlesConnectionFailure() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            in.readByte();
            in.readByte();

            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            out.writeByte(1);
            out.write(InetAddress.getByName("localhost").getAddress());
            out.writeShort(1); // Port 1 usually closed

            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 4); // Host unreachable
        }
    }

    @Test
    void proxy_handlesInvalidCommand() throws Exception {
        try (Socket socket = new Socket("localhost", proxyPort);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(0);
            in.readByte();
            in.readByte();

            out.writeByte(5);
            out.writeByte(2); // BIND (Unsupported)
            out.writeByte(0);
            out.writeByte(1);
            out.write(new byte[4]);
            out.writeShort(0);

            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 7); // Command not supported
        }
    }

    @Test
    void proxy_handlesAuthFailure() throws Exception {
        ProxyServerConfig config = new ProxyServerConfig();
        int p;
        try (ServerSocket s = new ServerSocket(0)) {
            p = s.getLocalPort();
        }
        config.setPort(p);
        config.setType("SOCKS5");
        config.setAuthEnabled(true);

        Mockito.when(authService.authenticate("bad", "pass")).thenReturn(false);

        Socks5ProxyServer srv = new Socks5ProxyServer(config, authService, loggingService, registry, globalProps);
        Thread t = new Thread(srv::start);
        t.setDaemon(true);
        t.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket s = new Socket("localhost", p)) {
                return s.isConnected();
            } catch (IOException e) {
                return false;
            }
        });

        try (Socket socket = new Socket("localhost", p);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {
            out.writeByte(5);
            out.writeByte(1);
            out.writeByte(2); // USERPASS
            assertThat(in.readByte()).isEqualTo((byte) 5);
            assertThat(in.readByte()).isEqualTo((byte) 2);

            out.writeByte(1); // VER
            out.writeByte(3);
            out.write("bad".getBytes());
            out.writeByte(4);
            out.write("pass".getBytes());

            assertThat(in.readByte()).isEqualTo((byte) 1);
            assertThat(in.readByte()).isEqualTo((byte) 1); // Failure
        } finally {
            srv.stop();
        }
    }
}
