package com.yuubin.proxy.config;

/**
 * Configuration for the administration server (health checks and metrics).
 */
public class AdminConfig {
    private boolean enabled = true;
    private int port = 9090;
    /** Bind address for the admin server. Defaults to loopback for security. */
    private String bindAddress = "127.0.0.1";

    /**
     * Checks if the admin server is enabled.
     * 
     * @return true if enabled, false otherwise.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether the admin server is enabled.
     * 
     * @param enabled true to enable, false to disable.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Gets the port for the admin server.
     * 
     * @return the port number.
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the port for the admin server.
     * 
     * @param port the port number.
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Gets the bind address for the admin server.
     * 
     * @return the bind address string.
     */
    public String getBindAddress() {
        return bindAddress;
    }

    /**
     * Sets the bind address for the admin server.
     * 
     * @param bindAddress the bind address string.
     */
    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }
}
