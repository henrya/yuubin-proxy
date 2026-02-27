package com.yuubin.proxy.core.exceptions;

/**
 * Thrown when there is an error in the application configuration.
 */
public class ConfigException extends ProxyException {
    /**
     * Constructs a new ConfigException with the specified detail message.
     * 
     * @param message the detail message.
     */
    public ConfigException(String message) {
        super(message);
    }

    /**
     * Constructs a new ConfigException with the specified detail message and cause.
     * 
     * @param message the detail message.
     * @param cause   the cause of the exception.
     */
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
