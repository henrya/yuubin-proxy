package com.yuubin.proxy.core.exceptions;

/**
 * Base exception for all proxy-related errors.
 */
public class ProxyException extends RuntimeException {
    public ProxyException(String message) {
        super(message);
    }

    public ProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
