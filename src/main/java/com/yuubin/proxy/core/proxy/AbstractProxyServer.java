package com.yuubin.proxy.core.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocketFactory;
import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.UpstreamProxyConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.utils.IoUtils;
import com.yuubin.proxy.core.utils.SslUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base abstract class for all proxy server implementations.
 * Handles server lifecycle, common configuration, and executor management.
 * Uses Virtual Threads for high-performance concurrency.
 */
public abstract class AbstractProxyServer implements ProxyServer {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** Configuration for this specific proxy server instance. */
    protected final ProxyServerConfig config;

    /** Global properties. */
    protected final YuubinProperties globalProps;

    /** Service for authenticating proxy users. */
    protected final AuthService authService;

    /** Service for logging proxy activity. */
    protected final LoggingService loggingService;

    /** Micrometer registry for metrics. */
    protected final MeterRegistry registry;

    /** Executor for handling client tasks, powered by Virtual Threads. */
    protected final ExecutorService executor;

    /** Semaphore to enforce the maximum number of concurrent connections. */
    protected final Semaphore connectionSemaphore;

    /** Set of active client sockets for graceful shutdown. */
    protected final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

    /** The main server socket listening for incoming connections. */
    protected ServerSocket serverSocket;

    private final Counter totalConnections;
    private final Counter connectionErrors;
    private final Meter activeGauge;
    private final Set<String> blacklistSet;

    /**
     * Latch released once {@code serverSocket.bind()} has completed (successfully
     * or not).
     * Allows {@code ProxyManager} to wait for the server to become ready before
     * registering it in the active-servers map.
     */
    private final CountDownLatch bindLatch = new CountDownLatch(1);
    /**
     * True if the last call to {@link #start()} successfully bound the server
     * socket.
     */
    private volatile boolean bindSuccess = false;

    /**
     * Initializes the proxy server with shared services and configuration.
     * 
     * @param config         The server configuration.
     * @param authService    The authentication provider.
     * @param loggingService The logging provider.
     * @param registry       The Micrometer meter registry.
     * @param globalProps    The global configuration.
     */
    protected AbstractProxyServer(ProxyServerConfig config, AuthService authService,
            LoggingService loggingService, MeterRegistry registry,
            YuubinProperties globalProps) {
        this.config = config;
        this.authService = authService;
        this.loggingService = loggingService;
        this.registry = registry;
        this.globalProps = globalProps;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.connectionSemaphore = new Semaphore(config.getMaxConnections());

        // Instrument metrics
        String type = config.getType().toLowerCase();
        String configName = config.getName() != null ? config.getName() : "unnamed";
        String name = configName.replace(" ", "_").toLowerCase();

        this.totalConnections = Counter.builder("proxy.connections.total")
                .tag("type", type)
                .tag("name", name)
                .description("Total number of accepted connections")
                .register(registry);

        this.connectionErrors = Counter.builder("proxy.connections.errors")
                .tag("type", type)
                .tag("name", name)
                .description("Total number of connection errors")
                .register(registry);

        this.activeGauge = Gauge.builder("proxy.connections.active", activeSockets, Set::size)
                .tag("type", type)
                .tag("name", name)
                .description("Current number of active connections")
                .register(registry);

        // Optimize blacklist lookups
        this.blacklistSet = new HashSet<>();
        if (globalProps.getGlobalBlacklist() != null) {
            blacklistSet.addAll(globalProps.getGlobalBlacklist());
        }
        if (config.getBlacklist() != null) {
            blacklistSet.addAll(config.getBlacklist());
        }
    }

    /**
     * Starts the proxy server. Binds to the configured port and enters the accept
     * loop.
     */
    @Override
    public void start() {
        try {
            if (!initializeServerSocket()) {
                return;
            }

            serverSocket.setReuseAddress(true);
            InetSocketAddress bindAddr = config.getBindAddress() != null
                    ? new InetSocketAddress(config.getBindAddress(), config.getPort())
                    : new InetSocketAddress(config.getPort());
            serverSocket.bind(bindAddr);
            bindSuccess = true;
            bindLatch.countDown(); // Signal: bind succeeded — server is ready to accept
            log.info("{} started on {}:{} (TLS: {})", getProxyName(),
                    config.getBindAddress() != null ? config.getBindAddress() : "0.0.0.0", config.getPort(),
                    config.isTlsEnabled());

            while (!serverSocket.isClosed()) {
                if (!acceptAndProcessNextClient()) {
                    break;
                }
            }
        } catch (IOException e) {
            bindLatch.countDown(); // Signal: bind failed — don't leave ProxyManager waiting forever
            connectionErrors.increment();
            log.error("{} server error on port {}: {}", getProxyName(), config.getPort(), e.getMessage(), e);
        }
    }

    /**
     * Accepts and processes the next incoming client connection.
     * 
     * @return {@code true} to continue the accept loop, {@code false} if the loop
     *         should terminate.
     */
    private boolean acceptAndProcessNextClient() {
        try {
            Socket client = serverSocket.accept();
            processClient(client);
            return true;
        } catch (SocketException e) {
            if (serverSocket.isClosed()) {
                return false;
            }
            connectionErrors.increment();
            log.error("{} accept error on port {}: {}", getProxyName(), config.getPort(), e.getMessage());
            return true;
        } catch (IOException e) {
            connectionErrors.increment();
            log.error("{} I/O error during accept on port {}: {}", getProxyName(), config.getPort(),
                    e.getMessage());
            return true;
        }
    }

    /**
     * Initializes the server socket, supporting TLS if enabled.
     * 
     * @return {@code true} if initialized successfully, {@code false} if TLS
     *         initialization failed.
     * @throws IOException If a plain ServerSocket cannot be created.
     */
    private boolean initializeServerSocket() throws IOException {
        if (config.isTlsEnabled()) {
            try {
                SSLServerSocketFactory ssf = SslUtils.createSslFactory(config, globalProps);
                serverSocket = ssf.createServerSocket();
            } catch (Exception e) {
                log.error("{} failed to initialize TLS: {}", getProxyName(), e.getMessage(), e);
                bindLatch.countDown(); // Signal: TLS init failed — don't hang ProxyManager
                return false;
            }
        } else {
            serverSocket = new ServerSocket();
        }
        return true;
    }

    /**
     * Waits for the server to finish binding to its port.
     * 
     * @param timeout Maximum time to wait.
     * @param unit    Unit for the timeout.
     * @return {@code true} if the bind completed successfully within the timeout.
     */
    public boolean awaitBind(long timeout, TimeUnit unit) {
        try {
            return bindLatch.await(timeout, unit) && bindSuccess;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void processClient(Socket client) {
        String remoteAddr = client.getInetAddress().getHostAddress();

        if (isBlacklisted(remoteAddr)) {
            log.warn("{} connection rejected from blacklisted IP: {}", getProxyName(), remoteAddr);
            IoUtils.closeQuietly(client, "blacklisted client socket");
            return;
        }

        totalConnections.increment();
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(config.getTimeout() > 0 ? config.getTimeout() : 60000);
        } catch (SocketException e) {
            log.debug("{} failed to configure client socket: {}", getProxyName(), e.getMessage());
        }

        if (connectionSemaphore.tryAcquire()) {
            activeSockets.add(client);
            executor.submit(() -> {
                try {
                    handleClient(client);
                } catch (Exception e) {
                    connectionErrors.increment();
                    log.error("{} unexpected error handling client {}: {}", getProxyName(), remoteAddr, e.getMessage(),
                            e);
                } finally {
                    activeSockets.remove(client);
                    connectionSemaphore.release();
                    IoUtils.closeQuietly(client, "client socket");
                }
            });
        } else {
            log.warn("{} connection limit reached ({})", getProxyName(), config.getMaxConnections());
            IoUtils.closeQuietly(client, "limit reached client socket");
        }
    }

    /**
     * Stops the proxy server. Closes the server socket and all active client
     * connections.
     */
    @Override
    public void stop() {
        log.info("Stopping {} server on port {}...", getProxyName(), config.getPort());
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.error("{} failed to close server socket: {}", getProxyName(), e.getMessage(), e);
        }

        // Close all active client connections to unblock IO
        for (Socket s : activeSockets) {
            IoUtils.closeQuietly(s);
        }
        activeSockets.clear();

        // Unregister metrics to prevent memory leaks
        registry.remove(totalConnections);
        registry.remove(connectionErrors);
        registry.remove(activeGauge);

        executor.shutdownNow();
        try {
            // Allow up to 5 s for active connections to finish their current transfer.
            // The previous 100 ms was too short for connections streaming large payloads.
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("{} executor did not terminate cleanly after 5 s", getProxyName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    @SuppressFBWarnings("EI_EXPOSE_REP")
    public ProxyServerConfig getConfig() {
        return config;
    }

    /**
     * Checks if a remote address is blacklisted.
     * 
     * @param remoteAddr The IP address to check.
     * @return True if blacklisted, false otherwise.
     */
    private boolean isBlacklisted(String remoteAddr) {
        return blacklistSet.contains(remoteAddr);
    }

    /**
     * Helper to connect to a target host, either directly or via an upstream proxy.
     * 
     * @param targetHost Target host name or IP.
     * @param port       Target port.
     * @return Connected socket.
     * @throws IOException If connection fails.
     */
    protected Socket connectToTarget(String targetHost, int port) throws IOException {
        UpstreamProxyConfig upstream = config.getUpstreamProxy();
        int timeout = config.getTimeout() > 0 ? config.getTimeout() : 10000;

        if (upstream != null) {
            log.debug("{} chaining via upstream proxy {}:{}", getProxyName(), upstream.getHost(), upstream.getPort());
            return UpstreamConnector.connect(targetHost, port, upstream, timeout);
        } else {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(targetHost, port), timeout);
                return socket;
            } catch (IOException e) {
                IoUtils.closeQuietly(socket, "target socket");
                throw e;
            }
        }
    }

    /**
     * Retrieves the protocol name.
     * 
     * @return Descriptive name of the proxy protocol (e.g., "HTTP", "SOCKS5").
     */
    protected abstract String getProxyName();

    /**
     * Handles an incoming client connection.
     * This method is executed within a virtual thread.
     * 
     * @param client The accepted client socket.
     */
    protected abstract void handleClient(Socket client);
}
