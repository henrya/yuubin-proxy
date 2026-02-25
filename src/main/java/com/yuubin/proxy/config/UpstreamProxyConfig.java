package com.yuubin.proxy.config;

import java.util.Objects;

/**
 * Configuration for an upstream proxy (proxy chaining).
 */
public class UpstreamProxyConfig {
    private String host;
    private int port;
    private String type = "HTTP";
    private String username;
    private String password;

    public UpstreamProxyConfig() {
    }

    public UpstreamProxyConfig(UpstreamProxyConfig other) {
        if (other != null) {
            this.host = other.host;
            this.port = other.port;
            this.type = other.type;
            this.username = other.username;
            this.password = other.password;
        }
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UpstreamProxyConfig that = (UpstreamProxyConfig) o;
        return port == that.port &&
                Objects.equals(host, that.host) &&
                Objects.equals(type, that.type) &&
                Objects.equals(username, that.username) &&
                Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, type, username, password);
    }
}
