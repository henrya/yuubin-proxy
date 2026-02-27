package com.yuubin.proxy.core.exceptions;

/**
 * Base exception for all proxy-related errors.
 */
public class ProxyException extends RuntimeException {
    /**
     * Constructs a new ProxyException with the specified detail message.
     * 
     * @param message the detail message.
     */
    public ProxyException(String message) {
        super(message);
    }

    /**
     * Constructs a new ProxyException with the specified detail message and cause.
     * 
     * @param message the detail message.
     * @param cause   the cause of the exception.
     */
    public ProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
