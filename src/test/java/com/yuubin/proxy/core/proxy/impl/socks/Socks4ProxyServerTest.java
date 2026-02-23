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

    @Test
    void proxy_handlesUnsupportedCommand() throws Exception {
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
            out.writeByte(2); // Unsupported command (only 1 is supported)
            out.writeShort(80);
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
    void proxy_handlesNullTerminatedStringEOF() throws Exception {
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
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            out.writeByte(4);
            out.writeByte(1);
            out.writeShort(80);
            out.write(new byte[] { 127, 0, 0, 1 });
            out.write("unterminated".getBytes());
            // No null terminator
            out.flush();
            socket.shutdownOutput();

            // Should close or return error. Since it throws EOFException in handleClient, it just closes.
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } finally {
            proxyServer.stop();
        }
    }

    @Test
    void proxy_handlesStringTooLong() throws Exception {
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
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            out.writeByte(4);
            out.writeByte(1);
            out.writeShort(80);
            out.write(new byte[] { 127, 0, 0, 1 });
            byte[] longUser = new byte[1026];
            java.util.Arrays.fill(longUser, (byte) 'a');
            out.write(longUser);
            out.flush();

            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } finally {
            proxyServer.stop();
        }
    }

    @Test
    void proxy_handlesTimeouts() throws Exception {
        int proxyPort;
        try (ServerSocket s1 = new ServerSocket(0)) {
            proxyPort = s1.getLocalPort();
        }

        LoggingService loggingService = Mockito.mock(LoggingService.class);
        AuthService authService = Mockito.mock(AuthService.class);
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.yuubin.proxy.config.YuubinProperties globalProps = new com.yuubin.proxy.config.YuubinProperties();
        
        // Test timeout > 0
        ProxyServerConfig config1 = new ProxyServerConfig();
        config1.setPort(proxyPort);
        config1.setType("SOCKS4");
        config1.setTimeout(100);

        Socks4ProxyServer server1 = new Socks4ProxyServer(config1, authService, loggingService, registry, globalProps);
        Thread t1 = new Thread(server1::start);
        t1.setDaemon(true);
        t1.start();
        
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Socket s = new Socket("localhost", proxyPort)) {
                return s.isConnected();
            } catch (IOException e) {
                return false;
            }
        });
        server1.stop();

        // Test timeout == -1 (unlimited)
        ProxyServerConfig config2 = new ProxyServerConfig();
        config2.setPort(proxyPort);
        config2.setType("SOCKS4");
        config2.setTimeout(-1);
        Socks4ProxyServer server2 = new Socks4ProxyServer(config2, authService, loggingService, registry, globalProps);
        server2.stop();

        // Test timeout == 0 (default 5000)
        ProxyServerConfig config3 = new ProxyServerConfig();
        config3.setPort(proxyPort);
        config3.setType("SOCKS4");
        config3.setTimeout(0);
        Socks4ProxyServer server3 = new Socks4ProxyServer(config3, authService, loggingService, registry, globalProps);
        server3.stop();
    }

    @Test
    void socks4Request_testEqualsAndHashCode() {
        byte[] ip = {127, 0, 0, 1};
        Socks4ProxyServer.Socks4Request req1 = new Socks4ProxyServer.Socks4Request((byte) 1, 80, ip, "user", "host");
        Socks4ProxyServer.Socks4Request req2 = new Socks4ProxyServer.Socks4Request((byte) 1, 80, ip, "user", "host");
        Socks4ProxyServer.Socks4Request req3 = new Socks4ProxyServer.Socks4Request((byte) 2, 80, ip, "user", "host");

        assertThat(req1).isEqualTo(req2);
        assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
        assertThat(req1).isNotEqualTo(req3);
        assertThat(req1.hashCode()).isNotEqualTo(req3.hashCode());
        assertThat(req1).isNotEqualTo(null);
        assertThat(req1).isNotEqualTo("not a request");
        assertThat(req1).isEqualTo(req1);
    }

    @Test
    void socks4Request_testToString() {
        byte[] ip = {127, 0, 0, 1};
        Socks4ProxyServer.Socks4Request req = new Socks4ProxyServer.Socks4Request((byte) 1, 80, ip, "user", "host");
        String str = req.toString();
        assertThat(str).contains("command=1");
        assertThat(str).contains("port=80");
        assertThat(str).contains("ipBytes=" + java.util.Arrays.toString(ip));
        assertThat(str).contains("userId='user'");
        assertThat(str).contains("targetHost='host'");
    }
}
