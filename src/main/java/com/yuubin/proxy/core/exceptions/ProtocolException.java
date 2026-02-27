package com.yuubin.proxy.core.exceptions;

/**
 * Thrown when a protocol-level error occurs (e.g., malformed SOCKS or HTTP
 * request).
 */
public class ProtocolException extends ProxyException {
    /**
     * Constructs a new ProtocolException with the specified detail message.
     * 
     * @param message the detail message.
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Constructs a new ProtocolException with the specified detail message and
     * cause.
     * 
     * @param message the detail message.
     * @param cause   the cause of the exception.
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
