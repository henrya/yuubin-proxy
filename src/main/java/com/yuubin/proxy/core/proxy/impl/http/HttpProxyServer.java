package com.yuubin.proxy.core.proxy.impl.http;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.UpstreamProxyConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.constants.HeaderConstants;
import com.yuubin.proxy.core.exceptions.ProtocolException;
import com.yuubin.proxy.core.exceptions.ProxyException;
import com.yuubin.proxy.core.proxy.AbstractProxyServer;
import com.yuubin.proxy.core.proxy.impl.http.filters.AuthFilter;
import com.yuubin.proxy.core.proxy.impl.http.filters.HttpFilter;
import com.yuubin.proxy.core.proxy.impl.http.filters.LoggingFilter;
import com.yuubin.proxy.core.proxy.impl.http.filters.RequestContext;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.utils.IoUtils;
import com.yuubin.proxy.entity.Rule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * High-performance L7 HTTP/HTTPS proxy implementation.
 * Supports forward proxying, reverse proxying via path-based rules,
 * HTTPS CONNECT tunneling, and manual redirect handling.
 * Now supports Host-based routing, Round-Robin Load Balancing, and WebSockets.
 */
public class HttpProxyServer extends AbstractProxyServer {

    /**
     * Bodies smaller than this threshold are buffered in memory; larger bodies are
     * streamed.
     */
    private static final int LARGE_BODY_THRESHOLD = 64 * 1024;

    // HTTP status codes used internally.
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_BAD_GATEWAY = 502;

    private static final String LOCATION_HEADER = "Location";
    private static final String BAD_GATEWAY_MSG = "Bad Gateway";
    private static final String INTERNAL_ERROR_MSG = "Internal Server Error";
    private static final String PATH_SEP = "/";

    /** Headers that should not be forwarded from client to backend. */
    private static final Set<String> DISALLOWED_HEADERS;

    /** Hop-by-hop headers that must be removed per RFC 2616. */
    private static final Set<String> HOP_BY_HOP_HEADERS;

    /** Standard HTTP reason phrases. */
    private static final Map<Integer, String> REASON_PHRASES;

    static {
        Set<String> disallowed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        disallowed.addAll(List.of(
                HeaderConstants.HOST.getValue(),
                HeaderConstants.PROXY_AUTHORIZATION.getValue(),
                HeaderConstants.CONNECTION.getValue(),
                HeaderConstants.CONTENT_LENGTH.getValue(),
                HeaderConstants.TRANSFER_ENCODING.getValue()));
        DISALLOWED_HEADERS = Collections.unmodifiableSet(disallowed);

        Set<String> hopByHop = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        hopByHop.addAll(List.of(
                HeaderConstants.CONNECTION.getValue(),
                HeaderConstants.KEEP_ALIVE.getValue(),
                HeaderConstants.PROXY_AUTHENTICATE.getValue(),
                HeaderConstants.PROXY_AUTHORIZATION.getValue(),
                HeaderConstants.TE.getValue(),
                HeaderConstants.TRAILERS.getValue(),
                HeaderConstants.TRANSFER_ENCODING.getValue(),
                HeaderConstants.UPGRADE.getValue()));
        HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(hopByHop);

        REASON_PHRASES = Map.ofEntries(
                Map.entry(200, "OK"), Map.entry(201, "Created"),
                Map.entry(204, "No Content"), Map.entry(301, "Moved Permanently"),
                Map.entry(302, "Found"), Map.entry(304, "Not Modified"),
                Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"),
                Map.entry(403, "Forbidden"), Map.entry(404, "Not Found"),
                Map.entry(405, "Method Not Allowed"), Map.entry(407, "Proxy Authentication Required"),
                Map.entry(408, "Request Timeout"), Map.entry(429, "Too Many Requests"),
                Map.entry(500, INTERNAL_ERROR_MSG), Map.entry(502, BAD_GATEWAY_MSG),
                Map.entry(503, "Service Unavailable"), Map.entry(504, "Gateway Timeout"));
    }

    private final HttpClient httpClient;
    private final List<HttpFilter> filters;
    private final Counter requestsTotal;
    private final Counter bytesSent;
    private final Counter bytesReceived;
    private final ScheduledExecutorService healthCheckExecutor;

    /**
     * Initializes the HTTP proxy server with configuration and shared services.
     * 
     * @param config         The server configuration.
     * @param authService    The authentication provider.
     * @param loggingService The logging provider.
     * @param registry       The Micrometer meter registry.
     * @param globalProps    The global configuration properties.
     */
    public HttpProxyServer(ProxyServerConfig config, AuthService authService, LoggingService loggingService,
            MeterRegistry registry, YuubinProperties globalProps) {
        super(config, authService, loggingService, registry, globalProps);

        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);

        if (config.getTimeout() > 0) {
            builder.connectTimeout(Duration.ofMillis(config.getTimeout()));
        }

        // Configure Upstream Proxy Chaining
        UpstreamProxyConfig upstream = config.getUpstreamProxy();
        if (upstream != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(upstream.getHost(), upstream.getPort())));
            if (upstream.getUsername() != null && upstream.getPassword() != null) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(upstream.getUsername(), upstream.getPassword().toCharArray());
                    }
                });
            }
        }

        this.httpClient = builder.build();
        this.filters = List.of(new AuthFilter(config, authService), new LoggingFilter(loggingService));

        String configName = config.getName() != null ? config.getName() : "unnamed";
        String tagName = configName.replace(" ", "_").toLowerCase();
        this.requestsTotal = Counter.builder("proxy.http.requests.total")
                .tag("name", tagName)
                .description("Total number of HTTP requests")
                .register(registry);
        this.bytesSent = Counter.builder("proxy.traffic.bytes.sent")
                .tag("name", tagName)
                .description("Total bytes sent to target")
                .register(registry);
        this.bytesReceived = Counter.builder("proxy.traffic.bytes.received")
                .tag("name", tagName)
                .description("Total bytes received from target")
                .register(registry);

        this.healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("health-check-" + configName).factory());
        startHealthChecks();
    }

    private void startHealthChecks() {
        if (config.getRules() == null) {
            return;
        }

        for (Rule rule : config.getRules()) {
            if (rule.getHealthCheckPath() != null && rule.getTargets() != null) {
                healthCheckExecutor.scheduleAtFixedRate(() -> runHealthCheck(rule),
                        0, rule.getHealthCheckInterval(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private void runHealthCheck(Rule rule) {
        if (rule.getTargets() == null) {
            return;
        }

        for (String target : rule.getTargets()) {
            String checkUrl = target;
            if (checkUrl.endsWith("/")) {
                checkUrl = checkUrl.substring(0, checkUrl.length() - 1);
            }
            checkUrl += rule.getHealthCheckPath();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(checkUrl))
                        .timeout(Duration.ofMillis(rule.getHealthCheckTimeout()))
                        .GET()
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 400) {
                    rule.markHealthy(target);
                } else {
                    log.warn("Health check failed for target {} (Status: {})", target, response.statusCode());
                    rule.markUnhealthy(target);
                }
            } catch (InterruptedException e) {
                log.warn("Health check interrupted for target {}: {}", target, e.getMessage());
                Thread.currentThread().interrupt();
                rule.markUnhealthy(target);
            } catch (Exception e) {
                log.warn("Health check error for target {}: {}", target, e.getMessage());
                rule.markUnhealthy(target);
            }
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdownNow();
        }
        if (httpClient != null) {
            try {
                httpClient.shutdownNow();
            } catch (Exception e) {
                log.debug("Error shutting down HTTP client: {}", e.getMessage());
            }
        }
        if (registry != null) {
            registry.remove(requestsTotal);
            registry.remove(bytesSent);
            registry.remove(bytesReceived);
        }
    }

    @Override
    protected String getProxyName() {
        return "HTTP";
    }

    /**
     * Main handler for HTTP client connections.
     * Manages the request/response loop and supports keep-alive.
     */
    @Override
    protected void handleClient(Socket client) {
        String remoteAddr = client.getInetAddress().getHostAddress();
        try (client) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            while (!client.isClosed() && processNextRequest(client, in, out, remoteAddr)) {
                // Loop continues as long as client is connected and request is processed
            }
        } catch (ProtocolException e) {
            log.warn("HTTP protocol error from {}: {}", remoteAddr, e.getMessage());
        } catch (ProxyException e) {
            log.error("HTTP proxy error for {}: {}", remoteAddr, e.getMessage());
        } catch (Exception e) {
            log.debug("Unexpected HTTP client error from {}: {}", remoteAddr, e.getMessage());
        }
    }

    private void handleWebSocket(OutputStream clientOut, InputStream clientIn, String firstLine,
            Map<String, String> headers, RequestContext context, Rule rule) throws IOException {
        String targetUrl = resolveTargetUrl(context, rule, clientOut);
        if (targetUrl == null) {
            return;
        }

        URI uri = URI.create(targetUrl);
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }

        try (Socket target = connectToTarget(host, port)) {
            target.setTcpNoDelay(true);
            OutputStream targetOut = target.getOutputStream();
            InputStream targetIn = new BufferedInputStream(target.getInputStream());

            // Forward initial request line and headers
            targetOut.write((firstLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            headers.forEach((k, v) -> {
                try {
                    targetOut.write((k + ": " + v + "\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // Ignored: best effort to write header
                }
            });
            targetOut.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            targetOut.flush();

            log.info("WebSocket Tunnel: {} -> {}", context.getUri(), targetUrl);
            IoUtils.relay(clientIn, clientOut, targetIn, targetOut, executor, bytesSent, bytesReceived);
        } catch (Exception e) {
            log.warn("WebSocket tunnel failed: {}", e.getMessage());
            writeErrorResponse(clientOut, HTTP_BAD_GATEWAY, BAD_GATEWAY_MSG);
        }
    }

    private static final int MAX_HTTP_HEADERS = 100;

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String line;
        int headerCount = 0;
        while ((line = IoUtils.readLine(in)) != null && !line.isEmpty()) {
            if (++headerCount > MAX_HTTP_HEADERS) {
                throw new ProtocolException("Too many HTTP headers (exceeds limit of " + MAX_HTTP_HEADERS + ")");
            }
            int idx = line.indexOf(":");
            if (idx != -1) {
                headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    /**
     * Establishes a TCP tunnel for HTTPS CONNECT requests.
     */
    private void handleConnect(Socket client, OutputStream clientOut, String firstLine) throws IOException {
        String[] parts = firstLine.split(" ");
        String authority = parts[1];
        String host = authority;
        int port = 443;
        if (authority.contains(":")) {
            String[] hp = authority.split(":");
            host = hp[0];
            port = Integer.parseInt(hp[1]);
        }

        try {
            Socket target = connectToTarget(host, port);
            target.setTcpNoDelay(true);
            clientOut.write(("HTTP/1.1 200 Connection Established\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            clientOut.flush();

            log.info("HTTPS Tunnel: {}", authority);
            IoUtils.relay(client, target, executor, bytesSent, bytesReceived);
        } catch (IOException e) {
            log.warn("Failed to establish HTTPS tunnel to {}: {}", authority, e.getMessage());
            writeErrorResponse(clientOut, HTTP_BAD_GATEWAY, BAD_GATEWAY_MSG);
        } catch (Exception e) {
            log.error("Unexpected error in HTTPS tunnel for {}", authority, e);
            writeErrorResponse(clientOut, HTTP_INTERNAL_ERROR, INTERNAL_ERROR_MSG);
        }
    }

    /**
     * Handles standard HTTP requests by forwarding them to the backend server.
     */
    private int handleRegularHttp(OutputStream clientOut, InputStream clientIn, RequestContext context,
            Rule rule) throws IOException {
        String targetUrl = resolveTargetUrl(context, rule, clientOut);
        if (targetUrl == null) {
            return 404;
        }

        try {
            HttpRequest.BodyPublisher bodyPublisher = createBodyPublisher(context.getHeaders(), clientIn);
            HttpResponse<InputStream> response = executeWithRedirects(context.getMethod(), targetUrl, bodyPublisher,
                    context.getHeaders(), context, rule);

            if (response == null) {
                writeErrorResponse(clientOut, HTTP_BAD_GATEWAY, BAD_GATEWAY_MSG);
                return HTTP_BAD_GATEWAY;
            }

            int statusCode = response.statusCode();
            forwardResponse(response, clientOut, context, rule);
            return statusCode;
        } catch (IOException e) {
            log.error("HTTP backend communication error: {}", e.getMessage());
            writeErrorResponse(clientOut, HTTP_BAD_GATEWAY, BAD_GATEWAY_MSG);
            return HTTP_BAD_GATEWAY;
        } catch (InterruptedException e) {
            log.error("HTTP backend communication interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            writeErrorResponse(clientOut, HTTP_BAD_GATEWAY, BAD_GATEWAY_MSG);
            return HTTP_BAD_GATEWAY;
        } catch (Exception e) {
            log.error("Unexpected error in HTTP proxying", e);
            writeErrorResponse(clientOut, HTTP_INTERNAL_ERROR, INTERNAL_ERROR_MSG);
            return HTTP_INTERNAL_ERROR;
        }
    }

    /**
     * Resolves the final target URL by applying routing rules or using the absolute
     * URI.
     * 
     * @param context   The request context.
     * @param rule      The matched rule.
     * @param clientOut The client output stream to write errors if needed.
     * @return The resolved target URL, or null if no matching rule was found and
     *         rules are strict.
     * @throws IOException If an I/O error occurs.
     */
    private String resolveTargetUrl(RequestContext context, Rule rule, OutputStream clientOut) throws IOException {
        URI uri = context.getUri();

        if (rule != null) {
            if (!rule.allowRequest(context.getRemoteAddr())) {
                writeErrorResponse(clientOut, HTTP_TOO_MANY_REQUESTS, "Too Many Requests");
                return null;
            }

            String targetBase = rule.getTarget(context.getRemoteAddr());
            if (targetBase == null) {
                writeErrorResponse(clientOut, HTTP_BAD_GATEWAY, BAD_GATEWAY_MSG);
                return null;
            }
            if (targetBase.endsWith("/")) {
                targetBase = targetBase.substring(0, targetBase.length() - 1);
            }
            String subPath = uri.getPath().substring(rule.getPath().length());
            if (!subPath.startsWith(PATH_SEP)) {
                subPath = PATH_SEP + subPath;
            }
            String targetUrl = targetBase + subPath;
            return uri.getQuery() != null ? targetUrl + "?" + uri.getQuery() : targetUrl;
        } else {
            if (config.getRules() != null && !config.getRules().isEmpty()) {
                writeErrorResponse(clientOut, HTTP_NOT_FOUND, "Not Found");
                return null;
            }
            return uri.toString();
        }
    }

    /**
     * Creates an appropriate HttpRequest.BodyPublisher based on the Content-Length.
     * Buffers small bodies (up to 1MB) and streams larger ones.
     * 
     * @param headers  The request headers.
     * @param clientIn The client input stream.
     * @return A BodyPublisher for the request.
     * @throws IOException If an I/O error occurs.
     */
    private HttpRequest.BodyPublisher createBodyPublisher(Map<String, String> headers, InputStream clientIn)
            throws IOException {
        if (!headers.containsKey(HeaderConstants.CONTENT_LENGTH.getValue())) {
            return HttpRequest.BodyPublishers.noBody();
        }

        long length = Long.parseLong(headers.get(HeaderConstants.CONTENT_LENGTH.getValue()));
        if (length < LARGE_BODY_THRESHOLD) { // Buffer small bodies; stream larger ones
            byte[] body = clientIn.readNBytes((int) length);
            return HttpRequest.BodyPublishers.ofByteArray(body);
        } else {
            return HttpRequest.BodyPublishers.ofInputStream(() -> new LimitInputStream(clientIn, length));
        }
    }

    /**
     * Executes the HTTP request and handles redirects manually up to the configured
     * limit.
     * 
     * @param method        The HTTP method.
     * @param targetUrl     The target URL.
     * @param bodyPublisher The request body publisher.
     * @param headers       The request headers.
     * @param context       The request context.
     * @param matchedRule   The rule that matched this request.
     * @return The final HttpResponse.
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the request is interrupted.
     */
    private HttpResponse<InputStream> executeWithRedirects(String method, String targetUrl,
            HttpRequest.BodyPublisher bodyPublisher, Map<String, String> headers, RequestContext context,
            Rule matchedRule) throws IOException, InterruptedException {
        int redirects = 0;
        String currentUrl = targetUrl;
        HttpResponse<InputStream> response = null;

        while (redirects <= config.getMaxRedirects()) {
            HttpRequest request = buildRequest(method, currentUrl, bodyPublisher, headers, context, matchedRule,
                    redirects);
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            boolean canRedirect = isRedirect(response) && redirects < config.getMaxRedirects();
            String location = canRedirect ? response.headers().firstValue(LOCATION_HEADER).orElse(null) : null;

            if (location != null) {
                currentUrl = URI.create(currentUrl).resolve(location).toString();
                redirects++;
                response.body().close();
            } else {
                break;
            }
        }
        return response;
    }

    private HttpRequest buildRequest(String method, String url, HttpRequest.BodyPublisher body,
            Map<String, String> headers, RequestContext context, Rule rule, int redirects) {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .method(method, redirects == 0 ? body : HttpRequest.BodyPublishers.noBody());

        if (config.getTimeout() > 0) {
            rb.timeout(Duration.ofMillis(config.getTimeout()));
        }

        headers.forEach((k, v) -> {
            if (isAllowedHeader(k)) {
                rb.header(k, v);
            }
        });

        // Standard mod_proxy-style X-Forwarded headers
        String existingXff = headers.get("X-Forwarded-For");
        String xff = (existingXff != null ? existingXff + ", " : "") + context.getRemoteAddr();
        rb.header("X-Forwarded-For", xff);
        rb.header("X-Forwarded-Proto", context.getUri().getScheme());
        rb.header("X-Forwarded-Host", context.getUri().getHost());

        if (rule != null && rule.getHeaders() != null) {
            rule.getHeaders().forEach(rb::header);
        }

        return rb.build();
    }

    /**
     * Checks if the response is a redirect (3xx status code with a Location
     * header).
     * 
     * @param response The response to check.
     * @return True if it is a redirect, false otherwise.
     */
    private boolean isRedirect(HttpResponse<?> response) {
        int status = response.statusCode();
        return status >= 300 && status < 400 && response.headers().firstValue(LOCATION_HEADER).isPresent();
    }

    /**
     * Forwards the backend response back to the client.
     * 
     * @param response  The backend response.
     * @param clientOut The client output stream.
     * @param context   The request context for instrumentation.
     * @param rule      The matched routing rule.
     * @throws IOException If an I/O error occurs.
     */
    private void forwardResponse(HttpResponse<InputStream> response, OutputStream clientOut, RequestContext context,
            Rule rule) throws IOException {
        int statusCode = response.statusCode();
        String reasonPhrase = REASON_PHRASES.getOrDefault(statusCode, "Unknown");
        clientOut.write(("HTTP/1.1 " + statusCode + " " + reasonPhrase + "\r\n").getBytes(StandardCharsets.US_ASCII));

        response.headers().map().forEach((k, vv) -> {
            if (!HOP_BY_HOP_HEADERS.contains(k)) {
                vv.forEach(v -> {
                    String value = v;
                    if (rule != null && rule.isReverse()
                            && (k.equalsIgnoreCase(LOCATION_HEADER) || k.equalsIgnoreCase("Content-Location"))) {
                        value = rewriteLocation(v, rule, context);
                    }
                    try {
                        clientOut.write((k + ": " + value + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException e) {
                        log.debug("Failed to write header {}: {}", k, e.getMessage());
                    }
                });
            }
        });
        clientOut.write("\r\n".getBytes(StandardCharsets.US_ASCII));

        CountingOutputStream countingOut = new CountingOutputStream(clientOut);
        try (InputStream responseBody = response.body()) {
            responseBody.transferTo(countingOut);
        }
        clientOut.flush();
        context.setBytes(countingOut.getCount());
    }

    /**
     * Rewrites the Location header for reverse proxy rules (ProxyPassReverse).
     * Checks every target in the rule (both the single {@code target} field and all
     * entries in {@code targets}) so that load-balanced backends are covered
     * correctly.
     */
    private String rewriteLocation(String location, Rule rule, RequestContext context) {
        for (String rawTarget : rule.getAllTargets()) {
            String base = rawTarget.endsWith("/")
                    ? rawTarget.substring(0, rawTarget.length() - 1)
                    : rawTarget;
            if (location.startsWith(base)) {
                String proxyBase = buildProxyBase(context, rule);
                String suffix = location.substring(base.length());
                if (proxyBase.endsWith("/") && suffix.startsWith("/")) {
                    proxyBase = proxyBase.substring(0, proxyBase.length() - 1);
                }
                return proxyBase + suffix;
            }
        }
        return location;
    }

    /**
     * Builds the public-facing base URL (scheme + host + port + rule path) for
     * Location rewriting.
     */
    private static String buildProxyBase(RequestContext context, Rule rule) {
        URI uri = context.getUri();
        StringBuilder base = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1
                && !(uri.getScheme().equals("http") && uri.getPort() == 80)
                && !(uri.getScheme().equals("https") && uri.getPort() == 443)) {
            base.append(':').append(uri.getPort());
        }
        base.append(rule.getPath());
        return base.toString();
    }

    /**
     * Writes a simple HTTP error response to the client.
     * 
     * @param out     The client output stream.
     * @param status  The HTTP status code.
     * @param message The reason phrase.
     * @throws IOException If an I/O error occurs.
     */
    private void writeErrorResponse(OutputStream out, int status, String message) throws IOException {
        String response = "HTTP/1.1 " + status + " " + message + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * Checks if a header is allowed to be forwarded to the backend.
     * 
     * @param k The header name.
     * @return True if allowed, false if disallowed or hop-by-hop.
     */
    private static boolean isAllowedHeader(String k) {
        return !DISALLOWED_HEADERS.contains(k);
    }

    /**
     * Finds a matching routing rule for the given host and path.
     * Rules with a specified host take precedence.
     * Matches the path exactly or as a prefix followed by a slash.
     * 
     * @param host The requested host.
     * @param path The request path.
     * @return The matching rule, or null if no match is found.
     */
    private Rule findMatchingRule(String host, String path) {
        if (config.getRules() == null) {
            return null;
        }
        return config.getRules().stream()
                .filter(r -> {
                    // Host check (if specified)
                    if (r.getHost() != null && !r.getHost().isEmpty() && !r.getHost().equalsIgnoreCase(host)) {
                        return false;
                    }
                    // Path check
                    return r.getPath().equals(path)
                            || (path.startsWith(r.getPath())
                                    && (r.getPath().equals("/")
                                            || path.charAt(r.getPath().length()) == '/'));
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * InputStream that limits reading to a certain number of bytes.
     */
    private static class LimitInputStream extends FilterInputStream {
        private long left;

        protected LimitInputStream(InputStream in, long limit) {
            super(in);
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int res = super.read();
            if (res != -1) {
                left--;
            }
            return res;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, left);
            int res = super.read(b, off, toRead);
            if (res != -1) {
                left -= res;
            }
            return res;
        }

        /**
         * Intentionally does not close the underlying stream. This wrapper sits on top
         * of
         * the persistent client connection stream, which must stay open across
         * keep-alive
         * requests. The client socket is closed by the outer {@code handleClient}
         * try-with-resources.
         */
        @Override
        public void close() throws IOException {
            // No-op by design — see Javadoc above.
        }

        public void drain() throws IOException {
            if (left > 0) {
                in.skipNBytes(left);
                left = 0;
            }
        }
    }

    /**
     * OutputStream that counts the number of bytes written.
     */
    private static class CountingOutputStream extends FilterOutputStream {
        private long count = 0;

        public CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
            count += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        public long getCount() {
            return count;
        }
    }

    private boolean processNextRequest(Socket client, InputStream in, OutputStream out, String remoteAddr)
            throws IOException {
        String firstLine = readRequestLine(in, remoteAddr);
        if (firstLine == null) {
            return false;
        }

        requestsTotal.increment();

        Map<String, String> headers = readRequestHeaders(in, remoteAddr);
        if (headers == null) {
            return false;
        }

        String[] parts = firstLine.split(" ");
        if (parts.length < 2) {
            return false;
        }
        String method = parts[0];
        String urlStr = parts[1];

        URI uri = parseUri(method, urlStr, headers, out);
        if (uri == null) {
            return true;
        }

        RequestContext context = new RequestContext(method, uri, headers, remoteAddr);

        if (!executeFilters(context, out, headers, in)) {
            return true;
        }

        if ("CONNECT".equalsIgnoreCase(method)) {
            handleConnect(client, out, firstLine);
            return false;
        }

        Rule rule = findMatchingRule(uri.getHost(), uri.getPath());
        if ("websocket".equalsIgnoreCase(headers.get(HeaderConstants.UPGRADE.getValue()))) {
            handleWebSocket(out, in, firstLine, headers, context, rule);
            return false;
        }

        long contentLength = parseContentLength(headers);
        LimitInputStream bodyStream = new LimitInputStream(in, contentLength);
        try {
            int statusCode = handleRegularHttp(out, bodyStream, context, rule);
            for (HttpFilter filter : filters) {
                filter.postHandle(context, statusCode);
            }
        } finally {
            bodyStream.drain();
        }

        return config.isKeepAlive() && !"close".equalsIgnoreCase(headers.get(HeaderConstants.CONNECTION.getValue()));
    }

    private boolean executeFilters(RequestContext context, OutputStream out, Map<String, String> headers,
            InputStream in) throws IOException {
        for (HttpFilter filter : filters) {
            try {
                if (!filter.preHandle(context, out)) {
                    skipRequestBody(in, headers);
                    return false;
                }
            } catch (ProxyException e) {
                log.warn("Filter error: {}", e.getMessage());
                return false;
            }
        }
        return true;
    }

    private String readRequestLine(InputStream in, String remoteAddr) {
        try {
            String line = IoUtils.readLine(in);
            if (line == null || line.isEmpty()) {
                return null;
            }
            return line;
        } catch (IOException e) {
            log.debug("Error reading request line from {}: {}", remoteAddr, e.getMessage());
            return null;
        }
    }

    private Map<String, String> readRequestHeaders(InputStream in, String remoteAddr) {
        try {
            return readHeaders(in);
        } catch (IOException e) {
            log.warn("Error reading headers from {}: {}", remoteAddr, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private URI parseUri(String method, String urlStr, Map<String, String> headers, OutputStream out) {
        try {
            if ("CONNECT".equalsIgnoreCase(method)) {
                return new URI("https://" + urlStr);
            }
            URI uri = new URI(urlStr);
            if (!uri.isAbsolute()) {
                String host = headers.get(HeaderConstants.HOST.getValue());
                uri = new URI("http://" + (host != null ? host : "localhost") + urlStr);
            }
            return uri;
        } catch (URISyntaxException e) {
            try {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException ignored) {
                // Ignore secondary error
            }
            log.debug("Invalid URI {}: {}", urlStr, e.getMessage());
            return null;
        }
    }

    private void skipRequestBody(InputStream in, Map<String, String> headers) {
        String clStr = headers.get(HeaderConstants.CONTENT_LENGTH.getValue());
        if (clStr != null) {
            try {
                long toSkip = Long.parseLong(clStr);
                if (toSkip > 0) {
                    in.skipNBytes(toSkip);
                }
            } catch (NumberFormatException e) {
                log.debug("Invalid Content-Length: {}", clStr);
            } catch (IOException e) {
                log.debug("Error skipping request body: {}", e.getMessage());
            }
        }
    }

    private long parseContentLength(Map<String, String> headers) {
        String clStr = headers.get(HeaderConstants.CONTENT_LENGTH.getValue());
        if (clStr != null) {
            try {
                return Long.parseLong(clStr);
            } catch (NumberFormatException e) {
                log.debug("Invalid Content-Length: {}", clStr);
            }
        }
        return 0;
    }
}
