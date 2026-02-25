package com.yuubin.proxy.core.proxy;

import com.yuubin.proxy.core.exceptions.ConfigException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.yuubin.proxy.config.UpstreamProxyConfig;
import com.yuubin.proxy.core.utils.IoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for establishing connections through upstream proxies.
 */
public class UpstreamConnector {
    private UpstreamConnector() {
        // Utility class
    }

    private static final Logger log = LoggerFactory.getLogger(UpstreamConnector.class);

    /**
     * Connects to a target through an upstream proxy.
     * 
     * @param targetHost Target host.
     * @param targetPort Target port.
     * @param upstream   Upstream proxy configuration.
     * @param timeout    Connection timeout in milliseconds.
     * @return Connected socket.
     * @throws IOException If connection fails.
     */
    public static Socket connect(String targetHost, int targetPort, UpstreamProxyConfig upstream, int timeout)
            throws IOException {
        if (!"HTTP".equalsIgnoreCase(upstream.getType())
                && (upstream.getUsername() != null || upstream.getPassword() != null)) {
            throw new ConfigException(
                    "Upstream SOCKS5 proxy authentication is not supported. "
                            + "Remove username/password from the upstream SOCKS5 proxy configuration.");
        }

        Socket proxySocket = new Socket();
        try {
            proxySocket.connect(new InetSocketAddress(upstream.getHost(), upstream.getPort()), timeout);

            if ("HTTP".equalsIgnoreCase(upstream.getType())) {
                performHttpHandshake(proxySocket, targetHost, targetPort, upstream);
            } else {
                performSocks5Handshake(proxySocket, targetHost, targetPort);
            }
            return proxySocket;
        } catch (IOException e) {
            IoUtils.closeQuietly(proxySocket);
            throw e;
        }
    }

    /**
     * Performs the HTTP CONNECT handshake with the upstream proxy.
     * 
     * @param socket   The socket connected to the proxy.
     * @param host     The target host.
     * @param port     The target port.
     * @param upstream The upstream proxy configuration.
     * @throws IOException If the handshake fails or the proxy returns an error.
     */
    private static void performHttpHandshake(Socket socket, String host, int port, UpstreamProxyConfig upstream)
            throws IOException {
        OutputStream out = socket.getOutputStream();
        StringBuilder sb = new StringBuilder();
        sb.append("CONNECT ").append(host).append(":").append(port).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(":").append(port).append("\r\n");

        if (upstream.getUsername() != null && upstream.getPassword() != null) {
            log.warn("Sending proxy credentials to upstream HTTP proxy ({}:{}) over a potentially "
                    + "plaintext connection. Use a TLS-capable upstream to protect credentials in transit.",
                    upstream.getHost(), upstream.getPort());
            String auth = upstream.getUsername() + ":" + upstream.getPassword();
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            sb.append("Proxy-Authorization: Basic ").append(encoded).append("\r\n");
        }
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String line = IoUtils.readLine(socket.getInputStream());
        if (line == null || !line.contains("200")) {
            throw new IOException("Failed to connect via upstream HTTP proxy: " + line);
        }
        // Skip remaining headers
        while ((line = IoUtils.readLine(socket.getInputStream())) != null && !line.isEmpty()) {
            // consume
        }
    }

    /**
     * Performs the SOCKS5 handshake with the upstream proxy.
     * Only supports 'No Authentication' for upstream SOCKS5 proxies currently.
     * 
     * @param socket The socket connected to the proxy.
     * @param host   The target host.
     * @param port   The target port.
     * @throws IOException If the handshake fails or the proxy returns an error.
     */
    private static void performSocks5Handshake(Socket socket, String host, int port) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        // Handshake: SOCKS5, 1 method, No Auth
        out.writeByte(5);
        out.writeByte(1);
        out.writeByte(0);
        out.flush();

        DataInputStream in = new DataInputStream(socket.getInputStream());
        if (in.readByte() != 5 || in.readByte() != 0) {
            throw new IOException("Upstream SOCKS5 proxy requires authentication (not supported)");
        }

        // Request: CONNECT, Domain name
        out.writeByte(5);
        out.writeByte(1);
        out.writeByte(0);
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        out.writeByte(3);
        out.writeByte(hostBytes.length);
        out.write(hostBytes);
        out.writeShort(port);
        out.flush();

        if (in.readByte() != 5 || in.readByte() != 0) {
            throw new IOException("Upstream SOCKS5 proxy failed to connect to " + host);
        }
        in.readByte(); // RSV
        byte atyp = in.readByte();
        if (atyp == 1) {
            in.readFully(new byte[4]);
        } else if (atyp == 3) {
            in.readFully(new byte[in.readUnsignedByte()]);
        } else if (atyp == 4) {
            in.readFully(new byte[16]);
        }
        in.readUnsignedShort(); // BND.PORT
    }
}
