package com.yuubin.proxy.core.constants;

/**
 * Common HTTP header names used by the proxy.
 */
public enum HeaderConstants {
    /** The Standard HTTP Host header. */
    HOST("Host"),
    /** Header used for credentials from client to proxy. */
    PROXY_AUTHORIZATION("Proxy-Authorization"),
    /** Header used for authentication challenges from proxy to client. */
    PROXY_AUTHENTICATE("Proxy-Authenticate"),
    /** Hop-by-hop Connection header. */
    CONNECTION("Connection"),
    /** Length of the entity body in bytes. */
    CONTENT_LENGTH("Content-Length"),
    /** Type of encoding used to transfer the entity. */
    TRANSFER_ENCODING("Transfer-Encoding"),
    /** Specifies the persistent connection parameters. */
    KEEP_ALIVE("Keep-Alive"),
    /** Specifies the transfer encodings the client is willing to accept. */
    TE("TE"),
    /** Specifies that a set of header fields is present in the trailer. */
    TRAILERS("Trailers"),
    /** Used by the client to request a protocol change. */
    UPGRADE("Upgrade");

    private final String value;

    HeaderConstants(String value) {
        this.value = value;
    }

    /**
     * Retrieves the standard string value of the header.
     * 
     * @return The standard string value of the header.
     */
    public String getValue() {
        return value;
    }
}
