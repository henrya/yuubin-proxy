package com.yuubin.proxy.config;

/**
 * Configuration for the administration server (health checks and metrics).
 */
public class AdminConfig {
    private boolean enabled = true;
    private int port = 9090;
    /** Bind address for the admin server. Defaults to loopback for security. */
    private String bindAddress = "127.0.0.1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }
}
