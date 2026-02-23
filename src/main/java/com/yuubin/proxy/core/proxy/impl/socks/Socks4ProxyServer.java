package com.yuubin.proxy.core.proxy.impl.socks;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.exceptions.ProtocolException;
import com.yuubin.proxy.core.exceptions.ProxyException;
import com.yuubin.proxy.core.proxy.AbstractProxyServer;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.utils.IoUtils;
import io.micrometer.core.instrument.Counter;

/**
 * Implementation of the SOCKS4 and SOCKS4a protocols.
 * Supports TCP CONNECT and SOCKS4a domain resolution extension.
 */
public class Socks4ProxyServer extends AbstractProxyServer {

    private final Counter requestsTotal;
    private final Counter bytesSent;
    private final Counter bytesReceived;

    /**
     * Initializes the SOCKS4 proxy server.
     * 
     * @param config         The server configuration.
     * @param authService    The authentication service.
     * @param loggingService The logging service.
     * @param registry       The Micrometer meter registry.
     * @param globalProps    The global configuration.
     */
    public Socks4ProxyServer(ProxyServerConfig config, AuthService authService, LoggingService loggingService,
            io.micrometer.core.instrument.MeterRegistry registry, YuubinProperties globalProps) {
        super(config, authService, loggingService, registry, globalProps);
        String configName = config.getName() != null ? config.getName() : "unnamed";
        String tagName = configName.replace(" ", "_").toLowerCase();
        this.requestsTotal = Counter.builder("proxy.socks4.requests.total")
                .tag("name", tagName)
                .description("Total number of SOCKS4 requests")
                .register(registry);
        this.bytesSent = Counter.builder("proxy.traffic.bytes.sent")
                .tag("name", tagName)
                .description("Total bytes sent to target")
                .register(registry);
        this.bytesReceived = Counter.builder("proxy.traffic.bytes.received")
                .tag("name", tagName)
                .description("Total bytes received from target")
                .register(registry);
    }

    @Override
    public void stop() {
        super.stop();
        if (registry != null) {
            registry.remove(requestsTotal);
            registry.remove(bytesSent);
            registry.remove(bytesReceived);
        }
    }

    @Override
    protected String getProxyName() {
        return "SOCKS4";
    }

    @Override
    protected void handleClient(Socket client) {
        String remoteAddr = client.getInetAddress().getHostAddress();
        try (Socket s = client) {
            setupSocket(s);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            Socks4Request request = readRequest(in);
            if (request == null) {
                return;
            }

            // Authentication check (using User ID as the identifier)
            if (config.isAuthEnabled() && !authService.userExists(request.userId())) {
                log.warn("SOCKS4 authentication failed for user: {}", request.userId());
                sendResponse(out, 91, 0, new byte[4]);
                return;
            }

            if (request.command() == 1) { // CONNECT
                handleConnect(s, out, request);
            } else {
                sendResponse(out, 91, 0, new byte[4]);
                throw new ProtocolException("SOCKS4 command not supported: " + request.command());
            }
        } catch (EOFException e) {
            log.debug("SOCKS4 client {} closed connection", remoteAddr);
        } catch (ProtocolException e) {
            log.warn("SOCKS4 protocol error from {}: {}", remoteAddr, e.getMessage());
        } catch (ProxyException e) {
            log.error("SOCKS4 proxy error for {}: {}", remoteAddr, e.getMessage());
        } catch (IOException e) {
            log.debug("SOCKS4 I/O error for {}: {}", remoteAddr, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in SOCKS4 handler for {}", remoteAddr, e);
        }
    }

    /**
     * Configures the client socket with the appropriate timeout settings.
     * 
     * @param client The client socket.
     * @throws SocketException If a socket error occurs.
     */
    private void setupSocket(Socket client) throws SocketException {
        if (config.getTimeout() > 0) {
            client.setSoTimeout(config.getTimeout());
        } else if (config.getTimeout() == -1) {
            client.setSoTimeout(0);
        } else {
            client.setSoTimeout(5000);
        }
    }

    /**
     * Reads the SOCKS4 request header from the client.
     * 
     * @param in The client input stream.
     * @return The parsed request object, or null if end of stream is reached.
     * @throws IOException If an I/O error occurs or the protocol version is
     *                     unsupported.
     */
    private Socks4Request readRequest(DataInputStream in) throws IOException {
        byte version;
        try {
            version = in.readByte();
        } catch (EOFException e) {
            return null;
        }

        requestsTotal.increment();
        if (version != 4) {
            throw new ProtocolException("Unsupported SOCKS version: " + version);
        }

        byte command = in.readByte();
        int port = in.readUnsignedShort();
        byte[] ipBytes = new byte[4];
        in.readFully(ipBytes);

        String userId = readNullTerminatedString(in);
        String targetHost;

        // SOCKS4a Extension: If IP is 0.0.0.x (x != 0), read domain name
        if (ipBytes[0] == 0 && ipBytes[1] == 0 && ipBytes[2] == 0 && ipBytes[3] != 0) {
            targetHost = readNullTerminatedString(in);
        } else {
            targetHost = InetAddress.getByAddress(ipBytes).getHostAddress();
        }

        return new Socks4Request(command, port, ipBytes, userId, targetHost);
    }

    /**
     * Reads a null-terminated string from the input stream.
     * 
     * @param in The input stream.
     * @return The string read.
     * @throws IOException If an I/O error occurs or EOF is reached before null
     *                     terminator.
     */
    private String readNullTerminatedString(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        int count = 0;
        final int maxStringLength = 1024;
        while ((b = in.read()) != 0) {
            if (b == -1) {
                throw new EOFException("End of stream while reading null-terminated string");
            }
            buffer.write(b);
            if (++count > maxStringLength) {
                throw new IOException("Null-terminated string exceeds maximum length of " + maxStringLength);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Internal representation of a SOCKS4 request.
     */
    record Socks4Request(byte command, int port, byte[] ipBytes, String userId, String targetHost) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Socks4Request that = (Socks4Request) o;
            return command == that.command && port == that.port && Arrays.equals(ipBytes, that.ipBytes)
                    && Objects.equals(userId, that.userId) && Objects.equals(targetHost, that.targetHost);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(command, port, userId, targetHost);
            result = 31 * result + Arrays.hashCode(ipBytes);
            return result;
        }

        @Override
        public String toString() {
            return "Socks4Request[" +
                    "command=" + command +
                    ", port=" + port +
                    ", ipBytes=" + Arrays.toString(ipBytes) +
                    ", userId='" + userId + '\'' +
                    ", targetHost='" + targetHost + '\'' +
                    ']';
        }
    }

    /**
     * Handles the SOCKS4 CONNECT command.
     * 
     * @param client  The client socket.
     * @param out     The client output stream.
     * @param request The parsed SOCKS4 request.
     * @throws IOException If an I/O error occurs.
     */
    private void handleConnect(Socket client, DataOutputStream out, Socks4Request request) throws IOException {
        try {
            Socket target = connectToTarget(request.targetHost(), request.port());
            target.setTcpNoDelay(true);

            // Reply success (90 = Request granted)
            sendResponse(out, 90, request.port(), request.ipBytes());

            loggingService.logSocks(client.getInetAddress().getHostAddress(),
                    request.targetHost() + ":" + request.port(), "SOCKS4", 90);

            client.setSoTimeout(0);
            IoUtils.relay(client, target, executor, bytesSent, bytesReceived);
        } catch (IOException e) {
            log.warn("SOCKS4 failed to connect to {}:{}: {}", request.targetHost(), request.port(), e.getMessage());
            sendResponse(out, 91, 0, new byte[4]);
        }
    }

    /**
     * Sends a SOCKS4 response to the client.
     * 
     * @param out    The client output stream.
     * @param status The SOCKS4 status code (90=granted, 91=failed).
     * @param port   The port (usually 0 for errors).
     * @param ip     The IP address bytes (usually 4 bytes of 0 for errors).
     * @throws IOException If an I/O error occurs.
     */
    private void sendResponse(DataOutputStream out, int status, int port, byte[] ip) throws IOException {
        out.writeByte(0);
        out.writeByte(status);
        out.writeShort(port);
        out.write(ip);
        out.flush();
    }
}
