package com.yuubin.proxy.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Root configuration object for the Yuubin Proxy.
 * Maps to the top-level structure of application.yml.
 */
public class YuubinProperties {
    /**
     * List of proxy server configurations.
     */
    private List<ProxyServerConfig> proxies;
    
    /**
     * Authentication configuration.
     */
    private AuthConfig auth = new AuthConfig();
    
    /**
     * Logging configuration.
     */
    private LoggingConfig logging = new LoggingConfig();

    /**
     * Administration and metrics configuration.
     */
    private AdminConfig admin = new AdminConfig();

    /**
     * Directory containing certificates (e.g. keystores).
     */
    private String certificatesPath = "certificates";

    /**
     * Global list of blacklisted IP addresses.
     */
    private List<String> globalBlacklist = new ArrayList<>();

    public List<ProxyServerConfig> getProxies() {
        return proxies == null ? null : Collections.unmodifiableList(proxies);
    }

    public void setProxies(List<ProxyServerConfig> proxies) {
        this.proxies = proxies == null ? null : new ArrayList<>(proxies);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public AuthConfig getAuth() {
        return auth;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public LoggingConfig getLogging() {
        return logging;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public AdminConfig getAdmin() {
        return admin;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public void setAdmin(AdminConfig admin) {
        this.admin = admin;
    }

    public String getCertificatesPath() {
        return certificatesPath;
    }

    public void setCertificatesPath(String certificatesPath) {
        this.certificatesPath = certificatesPath;
    }

    public List<String> getGlobalBlacklist() {
        return globalBlacklist == null ? null : Collections.unmodifiableList(globalBlacklist);
    }

    public void setGlobalBlacklist(List<String> globalBlacklist) {
        this.globalBlacklist = globalBlacklist == null ? null : new ArrayList<>(globalBlacklist);
    }
}
