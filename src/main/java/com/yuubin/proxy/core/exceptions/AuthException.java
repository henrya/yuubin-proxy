package com.yuubin.proxy.core.exceptions;

/**
 * Thrown when an error occurs during user authentication.
 */
public class AuthException extends ProxyException {
    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
