package com.yuubin.proxy.config;

/**
 * Configuration for the administration server (health checks and metrics).
 */
public class AdminConfig {
    private boolean enabled = true;
    private int port = 9090;

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
}
