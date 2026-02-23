package com.yuubin.proxy.core.exceptions;

/**
 * Thrown when a protocol-level error occurs (e.g., malformed SOCKS or HTTP request).
 */
public class ProtocolException extends ProxyException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
