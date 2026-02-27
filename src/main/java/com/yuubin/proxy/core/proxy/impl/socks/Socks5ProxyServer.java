package com.yuubin.proxy.core.proxy.impl.socks;

import java.io.DataInputStream;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

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
 * Implementation of the SOCKS5 protocol (RFC 1928).
 * Supports TCP CONNECT and Username/Password authentication (RFC 1929).
 */
public class Socks5ProxyServer extends AbstractProxyServer {

    /** SOCKS protocol version byte for SOCKS5. */
    private static final byte SOCKS5_VERSION = 5;

    private final Counter requestsTotal;
    private final Counter bytesSent;
    private final Counter bytesReceived;

    /**
     * Initializes the SOCKS5 proxy server.
     * 
     * @param config         The server configuration.
     * @param authService    The authentication service.
     * @param loggingService The logging service.
     * @param registry       The Micrometer meter registry.
     * @param globalProps    The global configuration.
     */
    public Socks5ProxyServer(ProxyServerConfig config, AuthService authService, LoggingService loggingService,
            io.micrometer.core.instrument.MeterRegistry registry, YuubinProperties globalProps) {
        super(config, authService, loggingService, registry, globalProps);
        String configName = config.getName() != null ? config.getName() : "unnamed";
        String tagName = configName.replace(" ", "_").toLowerCase();
        this.requestsTotal = Counter.builder("proxy.socks5.requests.total")
                .tag("name", tagName)
                .description("Total number of SOCKS5 requests")
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
        return "SOCKS5";
    }

    @Override
    protected void handleClient(Socket client) {
        String remoteAddr = client.getInetAddress().getHostAddress();
        try (Socket s = client) {
            setupSocket(s);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            if (!performHandshake(in, out, remoteAddr)) {
                return;
            }

            Socks5Request request = readRequest(in);

            if (request.cmd() == 1) { // CONNECT
                handleConnect(s, out, request);
            } else {
                sendErrorResponse(out, 7); // Command not supported
                throw new ProtocolException("SOCKS5 command not supported: " + request.cmd());
            }
        } catch (EOFException e) {
            log.debug("SOCKS5 client {} closed connection", remoteAddr);
        } catch (ProtocolException e) {
            log.warn("SOCKS5 protocol error from {}: {}", remoteAddr, e.getMessage());
        } catch (ProxyException e) {
            log.error("SOCKS5 proxy error for {}: {}", remoteAddr, e.getMessage());
        } catch (IOException e) {
            log.debug("SOCKS5 I/O error for {}: {}", remoteAddr, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in SOCKS5 handler for {}", remoteAddr, e);
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
     * Performs the initial SOCKS5 negotiation and authentication.
     * 
     * @param in         The client input stream.
     * @param out        The client output stream.
     * @param remoteAddr The client's IP address for logging.
     * @return True if handshake and authentication succeeded, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    private boolean performHandshake(DataInputStream in, DataOutputStream out, String remoteAddr) throws IOException {
        byte version;
        try {
            version = in.readByte();
        } catch (EOFException e) {
            return false;
        }

        requestsTotal.increment();
        if (version != SOCKS5_VERSION) {
            throw new ProtocolException("Unsupported SOCKS version: " + version);
        }

        int numMethods = in.readUnsignedByte();
        byte[] methods = new byte[numMethods];
        in.readFully(methods);

        boolean supportUserPass = false;
        for (byte m : methods) {
            if (m == 2) {
                supportUserPass = true;
            }
        }

        if (config.isAuthEnabled()) {
            if (!supportUserPass) {
                out.writeByte(SOCKS5_VERSION);
                out.writeByte(0xFF);
                out.flush();
                throw new ProtocolException("Auth required but USER/PASS not supported");
            }
            out.writeByte(SOCKS5_VERSION);
            out.writeByte(2);
            out.flush();
            return authenticate(in, out, remoteAddr);
        } else {
            out.writeByte(SOCKS5_VERSION);
            out.writeByte(0);
            out.flush();
            return true;
        }
    }

    /**
     * Reads the SOCKS5 request header from the client.
     * 
     * @param in The client input stream.
     * @return The parsed request object.
     * @throws IOException If an I/O error occurs or the address type is
     *                     unsupported.
     */
    private Socks5Request readRequest(DataInputStream in) throws IOException {
        in.readByte(); // VER
        byte cmd = in.readByte();
        in.readByte(); // RSV
        byte atyp = in.readByte();

        String targetHost = switch (atyp) {
            case 1 -> { // IPv4
                byte[] ip = new byte[4];
                in.readFully(ip);
                yield InetAddress.getByAddress(ip).getHostAddress();
            }
            case 3 -> { // DOMAINNAME
                int len = in.readUnsignedByte();
                byte[] hostBytes = new byte[len];
                in.readFully(hostBytes);
                yield new String(hostBytes, StandardCharsets.UTF_8);
            }
            case 4 -> { // IPv6
                byte[] ip = new byte[16];
                in.readFully(ip);
                yield InetAddress.getByAddress(ip).getHostAddress();
            }
            default -> throw new ProtocolException("SOCKS5 address type not supported: " + atyp);
        };

        int port = in.readUnsignedShort();
        return new Socks5Request(cmd, atyp, targetHost, port);
    }

    private record Socks5Request(byte cmd, byte atyp, String host, int port) {
    }

    /**
     * Handles the SOCKS5 CONNECT command.
     * 
     * @param client  The client socket.
     * @param out     The client output stream.
     * @param request The parsed SOCKS5 request.
     * @throws IOException If an I/O error occurs.
     */
    private void handleConnect(Socket client, DataOutputStream out, Socks5Request request) throws IOException {
        try (Socket target = connectToTarget(request.host(), request.port())) {
            target.setTcpNoDelay(true);

            out.writeByte(5);
            out.writeByte(0); // Succeeded
            out.writeByte(0); // RSV

            byte[] localAddr = target.getLocalAddress().getAddress();
            out.writeByte(localAddr.length == 4 ? 1 : 4);
            out.write(localAddr);
            out.writeShort(target.getLocalPort());
            out.flush();

            loggingService.logSocks(client.getInetAddress().getHostAddress(), request.host() + ":" + request.port(),
                    "SOCKS5", 0);

            client.setSoTimeout(0);
            IoUtils.relay(client, target, executor, bytesSent, bytesReceived);
        } catch (IOException e) {
            log.warn("SOCKS5 failed to connect to {}:{}: {}", request.host(), request.port(), e.getMessage());
            sendErrorResponse(out, 4); // Host unreachable
        }
    }

    /**
     * Sends a SOCKS5 error response to the client.
     * 
     * @param out     The client output stream.
     * @param errCode The SOCKS5 error code.
     * @throws IOException If an I/O error occurs.
     */
    private void sendErrorResponse(DataOutputStream out, int errCode) throws IOException {
        out.writeByte(5);
        out.writeByte(errCode);
        out.writeByte(0);
        out.writeByte(1);
        out.write(new byte[4]);
        out.writeShort(0);
        out.flush();
    }

    /**
     * Performs the Username/Password sub-negotiation (RFC 1929).
     * 
     * @param in         The client input stream.
     * @param out        The client output stream.
     * @param remoteAddr The client's IP address for logging.
     * @return True if authentication succeeded, false otherwise.
     * @throws IOException If an I/O error occurs.
     */
    private boolean authenticate(DataInputStream in, DataOutputStream out, String remoteAddr) throws IOException {
        byte ver = in.readByte();
        if (ver != 1) {
            return false;
        }

        // RFC 1929 §2: username and password lengths are each encoded as a single
        // unsigned byte,
        // so both fields are bounded to 0–255 bytes by the wire protocol — no OOM risk.
        // RFC 1929 also requires each length to be at least 1.
        int userLen = in.readUnsignedByte();
        if (userLen == 0) {
            throw new ProtocolException("RFC 1929: username length must be at least 1");
        }
        byte[] userBytes = new byte[userLen];
        in.readFully(userBytes);
        String user = new String(userBytes, StandardCharsets.UTF_8);

        int passLen = in.readUnsignedByte();
        if (passLen == 0) {
            throw new ProtocolException("RFC 1929: password length must be at least 1");
        }
        byte[] passBytes = new byte[passLen];
        in.readFully(passBytes);
        String pass = new String(passBytes, StandardCharsets.UTF_8);

        boolean ok = authService.authenticate(user, pass);

        out.writeByte(1);
        out.writeByte(ok ? 0 : 1);
        out.flush();

        if (!ok) {
            log.warn("SOCKS5 authentication failed for {}", remoteAddr);
        }
        return ok;
    }
}
