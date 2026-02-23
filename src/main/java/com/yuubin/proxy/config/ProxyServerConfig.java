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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<Rule> getRules() {
        return rules == null ? null : Collections.unmodifiableList(rules);
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? null : new ArrayList<>(rules);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public List<String> getBlacklist() {
        return blacklist == null ? null : Collections.unmodifiableList(blacklist);
    }

    public void setBlacklist(List<String> blacklist) {
        this.blacklist = blacklist == null ? null : new ArrayList<>(blacklist);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public UpstreamProxyConfig getUpstreamProxy() {
        return upstreamProxy;
    }

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
        return Objects.hash(name, port, type, rules, authEnabled, keepAlive, timeout, maxRedirects, maxConnections, bindAddress, tlsEnabled, keystorePath, keystorePassword, blacklist, upstreamProxy);
    }
}
