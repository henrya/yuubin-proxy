package com.yuubin.proxy.config;

import com.yuubin.proxy.entity.Rule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a specific proxy server instance.
 */
public class ProxyServerConfig {
    /** Unique name for the proxy server. */
    private String name;

    /** Port to listen on. */
    private int port;

    /** Protocol type (HTTP, SOCKS4, SOCKS5, or Custom). */
    private String type;

    /** Routing rules for HTTP proxies. */
    private List<Rule> rules;

    /** Whether authentication is required for this proxy. */
    private boolean authEnabled = false;

    /** Whether to use TCP keep-alive. */
    private boolean keepAlive = true;

    /** Connection and request timeout in milliseconds. Default is 60s. */
    private int timeout = 60000;

    /** Maximum number of HTTP redirects to follow. Default is 0. */
    private int maxRedirects = 0;

    /** Maximum concurrent connections. Default is 10,000. */
    private int maxConnections = 10000;

    /** Local IP address to bind to. Null means all interfaces. */
    private String bindAddress;

    /** Whether to enable TLS for this proxy. */
    private boolean tlsEnabled = false;

    /** Path to the PKCS12 keystore file. */
    private String keystorePath;

    /** Password for the keystore. */
    private String keystorePassword;

    /** List of blacklisted IP addresses. */
    private List<String> blacklist = new ArrayList<>();

    /** Upstream proxy for chaining. */
    private UpstreamProxyConfig upstreamProxy;

    /**
     * Gets the unique name for the proxy server.
     * 
     * @return the proxy server name.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the unique name for the proxy server.
     * 
     * @param name the proxy server name.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the port to listen on.
     * 
     * @return the port number.
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the port to listen on.
     * 
     * @param port the port number.
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Gets the protocol type (HTTP, SOCKS4, SOCKS5, or Custom).
     * 
     * @return the protocol type string.
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the protocol type (HTTP, SOCKS4, SOCKS5, or Custom).
     * 
     * @param type the protocol type string.
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Gets the routing rules for HTTP proxies.
     * 
     * @return the unmodifiable list of rules.
     */
    public List<Rule> getRules() {
        return rules == null ? null : Collections.unmodifiableList(rules);
    }

    /**
     * Sets the routing rules for HTTP proxies.
     * 
     * @param rules the list of rules.
     */
    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? null : new ArrayList<>(rules);
    }

    /**
     * Checks if authentication is enabled for this proxy.
     * 
     * @return true if enabled, false otherwise.
     */
    public boolean isAuthEnabled() {
        return authEnabled;
    }

    /**
     * Sets whether authentication is required for this proxy.
     * 
     * @param authEnabled true to enable, false to disable.
     */
    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    /**
     * Checks if TCP keep-alive is enabled.
     * 
     * @return true if enabled, false otherwise.
     */
    public boolean isKeepAlive() {
        return keepAlive;
    }

    /**
     * Sets whether to use TCP keep-alive.
     * 
     * @param keepAlive true to enable, false to disable.
     */
    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    /**
     * Gets the connection and request timeout in milliseconds.
     * 
     * @return the timeout in milliseconds.
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Sets the connection and request timeout in milliseconds.
     * 
     * @param timeout the timeout in milliseconds.
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * Gets the maximum number of HTTP redirects to follow.
     * 
     * @return the maximum redirects.
     */
    public int getMaxRedirects() {
        return maxRedirects;
    }

    /**
     * Sets the maximum number of HTTP redirects to follow.
     * 
     * @param maxRedirects the maximum redirects.
     */
    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    /**
     * Gets the maximum concurrent connections.
     * 
     * @return the maximum connections.
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Sets the maximum concurrent connections.
     * 
     * @param maxConnections the maximum connections.
     */
    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /**
     * Gets the local IP address to bind to.
     * 
     * @return the bind address string, or null if all interfaces.
     */
    public String getBindAddress() {
        return bindAddress;
    }

    /**
     * Sets the local IP address to bind to.
     * 
     * @param bindAddress the bind address string.
     */
    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    /**
     * Checks if TLS is enabled for this proxy.
     * 
     * @return true if enabled, false otherwise.
     */
    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    /**
     * Sets whether to enable TLS for this proxy.
     * 
     * @param tlsEnabled true to enable, false to disable.
     */
    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    /**
     * Gets the path to the PKCS12 keystore file.
     * 
     * @return the keystore path.
     */
    public String getKeystorePath() {
        return keystorePath;
    }

    /**
     * Sets the path to the PKCS12 keystore file.
     * 
     * @param keystorePath the keystore path.
     */
    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    /**
     * Gets the password for the keystore.
     * 
     * @return the keystore password.
     */
    public String getKeystorePassword() {
        return keystorePassword;
    }

    /**
     * Sets the password for the keystore.
     * 
     * @param keystorePassword the keystore password.
     */
    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    /**
     * Gets the list of blacklisted IP addresses.
     * 
     * @return the unmodifiable list of blacklisted IPs.
     */
    public List<String> getBlacklist() {
        return blacklist == null ? null : Collections.unmodifiableList(blacklist);
    }

    /**
     * Sets the list of blacklisted IP addresses.
     * 
     * @param blacklist the list of blacklisted IPs.
     */
    public void setBlacklist(List<String> blacklist) {
        this.blacklist = blacklist == null ? null : new ArrayList<>(blacklist);
    }

    /**
     * Gets the upstream proxy for chaining.
     * 
     * @return the upstream proxy configuration.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP")
    public UpstreamProxyConfig getUpstreamProxy() {
        return upstreamProxy;
    }

    /**
     * Sets the upstream proxy for chaining.
     * 
     * @param upstreamProxy the upstream proxy configuration.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public void setUpstreamProxy(UpstreamProxyConfig upstreamProxy) {
        this.upstreamProxy = upstreamProxy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProxyServerConfig that = (ProxyServerConfig) o;
        return port == that.port &&
                authEnabled == that.authEnabled &&
                keepAlive == that.keepAlive &&
                timeout == that.timeout &&
                maxRedirects == that.maxRedirects &&
                maxConnections == that.maxConnections &&
                tlsEnabled == that.tlsEnabled &&
                Objects.equals(name, that.name) &&
                Objects.equals(type, that.type) &&
                Objects.equals(rules, that.rules) &&
                Objects.equals(bindAddress, that.bindAddress) &&
                Objects.equals(keystorePath, that.keystorePath) &&
                Objects.equals(keystorePassword, that.keystorePassword) &&
                Objects.equals(blacklist, that.blacklist) &&
                Objects.equals(upstreamProxy, that.upstreamProxy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, port, type, rules, authEnabled, keepAlive, timeout, maxRedirects, maxConnections,
                bindAddress, tlsEnabled, keystorePath, keystorePassword, blacklist, upstreamProxy);
    }
}
