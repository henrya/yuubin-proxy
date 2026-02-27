package com.yuubin.proxy.core.exceptions;

/**
 * Thrown when an error occurs during user authentication.
 */
public class AuthException extends ProxyException {
    /**
     * Constructs a new AuthException with the specified detail message.
     * 
     * @param message the detail message.
     */
    public AuthException(String message) {
        super(message);
    }

    /**
     * Constructs a new AuthException with the specified detail message and cause.
     * 
     * @param message the detail message.
     * @param cause   the cause of the exception.
     */
    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
