package com.yuubin.proxy.core.proxy.impl.socks;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
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

class Socks4ProxyServerTest {

    @Test
    void proxy_handlesSocks4Connection() throws Exception {
        int proxyPort;
        int targetPort;
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
            targetPort = s2.getLocalPort();
        }

        LoggingService loggingService = Mockito.mock(LoggingService.class);
        AuthService authService = Mockito.mock(AuthService.class);
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.yuubin.proxy.config.YuubinProperties globalProps = new com.yuubin.proxy.config.YuubinProperties();
        ProxyServerConfig config = new ProxyServerConfig();
        config.setPort(proxyPort);
        config.setType("SOCKS4");

        Socks4ProxyServer proxyServer = new Socks4ProxyServer(config, authService, loggingService, registry,
                globalProps);
        Thread proxyThread = new Thread(proxyServer::start);
        proxyThread.setDaemon(true);
        proxyThread.start();

        try (ServerSocket targetServer = new ServerSocket(targetPort)) {
            Thread targetThread = new Thread(() -> {
                try (Socket s = targetServer.accept();
                        OutputStream os = s.getOutputStream()) {
                    os.write("Hello SOCKS4".getBytes());
                    os.flush();
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

            try (Socket socket = new Socket("localhost", proxyPort);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream())) {

                out.writeByte(4); // VER
                out.writeByte(1); // CMD: CONNECT
                out.writeShort(targetPort);
                out.write(InetAddress.getByName("localhost").getAddress());
                out.writeByte(0); // NULL terminated user
                out.flush();

                assertThat(in.readByte()).isEqualTo((byte) 0);
                assertThat(in.readByte()).isEqualTo((byte) 90); // GRANTED
                in.readShort(); // Port
                byte[] addr = new byte[4];
                in.readFully(addr);

                byte[] response = new byte[12];
                in.readFully(response);
                assertThat(new String(response)).isEqualTo("Hello SOCKS4");
            }
        } finally {
            proxyServer.stop();
        }
    }

    @Test
    void proxy_handlesInvalidVersion() throws Exception {
        int proxyPort;
        try (ServerSocket s1 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
        }

        LoggingService loggingService = Mockito.mock(LoggingService.class);
        AuthService authService = Mockito.mock(AuthService.class);
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.yuubin.proxy.config.YuubinProperties globalProps = new com.yuubin.proxy.config.YuubinProperties();
        ProxyServerConfig config = new ProxyServerConfig();
        config.setPort(proxyPort);
        config.setType("SOCKS4");

        Socks4ProxyServer proxyServer = new Socks4ProxyServer(config, authService, loggingService, registry,
                globalProps);
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

        try (Socket socket = new Socket("localhost", proxyPort)) {
            socket.getOutputStream().write(5); // SOCKS5 byte instead of 4
            socket.getOutputStream().flush();
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } finally {
            proxyServer.stop();
        }
    }

    @Test
    void proxy_handlesSocks4a() throws Exception {
        int proxyPort;
        int targetPort;
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
            targetPort = s2.getLocalPort();
        }

        LoggingService loggingService = Mockito.mock(LoggingService.class);
        AuthService authService = Mockito.mock(AuthService.class);
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.yuubin.proxy.config.YuubinProperties globalProps = new com.yuubin.proxy.config.YuubinProperties();
        ProxyServerConfig config = new ProxyServerConfig();
        config.setPort(proxyPort);
        config.setType("SOCKS4");

        Socks4ProxyServer proxyServer = new Socks4ProxyServer(config, authService, loggingService, registry,
                globalProps);
        Thread t1 = new Thread(proxyServer::start);
        t1.setDaemon(true);
        t1.start();

        try (ServerSocket targetServer = new ServerSocket(targetPort)) {
            Thread t2 = new Thread(() -> {
                try {
                    targetServer.accept().close();
                } catch (IOException ignored) {
                }
            });
            t2.setDaemon(true);
            t2.start();

            await().atMost(Duration.ofSeconds(10)).until(() -> {
                try (Socket s = new Socket("localhost", proxyPort)) {
                    return s.isConnected();
                } catch (IOException e) {
                    return false;
                }
            });

            try (Socket socket = new Socket("localhost", proxyPort);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream())) {

                out.writeByte(4);
                out.writeByte(1);
                out.writeShort(targetPort);
                out.write(new byte[] { 0, 0, 0, 1 }); // SOCKS4a indicator
                out.writeByte(0); // empty user
                out.write("localhost".getBytes());
                out.writeByte(0);
                out.flush();

                assertThat(in.readByte()).isEqualTo((byte) 0);
                assertThat(in.readByte()).isEqualTo((byte) 90);
            }
        } finally {
            proxyServer.stop();
        }
    }

    @Test
    void proxy_handlesConnectionFailure() throws Exception {
        int proxyPort;
        try (ServerSocket s1 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
        }

        LoggingService loggingService = Mockito.mock(LoggingService.class);
        AuthService authService = Mockito.mock(AuthService.class);
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.yuubin.proxy.config.YuubinProperties globalProps = new com.yuubin.proxy.config.YuubinProperties();
        ProxyServerConfig config = new ProxyServerConfig();
        config.setPort(proxyPort);
        config.setType("SOCKS4");

        Socks4ProxyServer proxyServer = new Socks4ProxyServer(config, authService, loggingService, registry,
                globalProps);
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

        try (Socket socket = new Socket("localhost", proxyPort);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream())) {

            out.writeByte(4);
            out.writeByte(1);
            out.writeShort(1); // Bad port
            out.write(new byte[] { 127, 0, 0, 1 });
            out.writeByte(0);
            out.flush();

            assertThat(in.readByte()).isEqualTo((byte) 0);
            assertThat(in.readByte()).isEqualTo((byte) 91); // FAILED
        } finally {
            proxyServer.stop();
        }
    }

    @Test
    void proxy_handlesAuth() throws Exception {
        int proxyPort;
        int targetPort;
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
            targetPort = s2.getLocalPort();
        }

        LoggingService loggingService = Mockito.mock(LoggingService.class);
        AuthService authService = Mockito.mock(AuthService.class);
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.yuubin.proxy.config.YuubinProperties globalProps = new com.yuubin.proxy.config.YuubinProperties();
        ProxyServerConfig config = new ProxyServerConfig();
        config.setPort(proxyPort);
        config.setType("SOCKS4");
        config.setAuthEnabled(true);

        Mockito.when(authService.userExists("user")).thenReturn(true);
        Mockito.when(authService.userExists("bad")).thenReturn(false);

        Socks4ProxyServer proxyServer = new Socks4ProxyServer(config, authService, loggingService, registry,
                globalProps);
        Thread t1 = new Thread(proxyServer::start);
        t1.setDaemon(true);
        t1.start();

        try (ServerSocket targetServer = new ServerSocket(targetPort)) {
            Thread t2 = new Thread(() -> {
                try {
                    targetServer.accept().close();
                } catch (IOException ignored) {
                }
            });
            t2.setDaemon(true);
            t2.start();

            await().atMost(Duration.ofSeconds(10)).until(() -> {
                try (Socket s = new Socket("localhost", proxyPort)) {
                    return s.isConnected();
                } catch (IOException e) {
                    return false;
                }
            });

            // Success
            try (Socket socket = new Socket("localhost", proxyPort);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream())) {
                out.writeByte(4);
                out.writeByte(1);
                out.writeShort(targetPort);
                out.write(new byte[] { 127, 0, 0, 1 });
                out.write("user".getBytes());
                out.writeByte(0);
                out.flush();
                assertThat(in.readByte()).isEqualTo((byte) 0);
                assertThat(in.readByte()).isEqualTo((byte) 90); // SUCCESS
            }

            // Failure
            try (Socket socket = new Socket("localhost", proxyPort);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream())) {
                out.writeByte(4);
                out.writeByte(1);
                out.writeShort(targetPort);
                out.write(new byte[] { 127, 0, 0, 1 });
                out.write("bad".getBytes());
                out.writeByte(0);
                out.flush();
                assertThat(in.readByte()).isEqualTo((byte) 0);
                assertThat(in.readByte()).isEqualTo((byte) 91); // FAILED
            }
        } finally {
            proxyServer.stop();
        }
    }
}
