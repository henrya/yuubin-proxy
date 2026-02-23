package com.yuubin.proxy.core.constants;

/**
 * Common HTTP header names used by the proxy.
 */
public enum HeaderConstants {
    HOST("Host"),
    PROXY_AUTHORIZATION("Proxy-Authorization"),
    PROXY_AUTHENTICATE("Proxy-Authenticate"),
    CONNECTION("Connection"),
    CONTENT_LENGTH("Content-Length"),
    TRANSFER_ENCODING("Transfer-Encoding"),
    KEEP_ALIVE("Keep-Alive"),
    TE("TE"),
    TRAILERS("Trailers"),
    UPGRADE("Upgrade");

    private final String value;

    HeaderConstants(String value) {
        this.value = value;
    }

    /**
     * Retrieves the standard string value of the header.
     * @return The standard string value of the header.
     */
    public String getValue() {
        return value;
    }
}
