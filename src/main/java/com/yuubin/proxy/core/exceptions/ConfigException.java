package com.yuubin.proxy.core.exceptions;

/**
 * Thrown when there is an error in the application configuration.
 */
public class ConfigException extends ProxyException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
