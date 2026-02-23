package com.yuubin.proxy.core.proxy.impl.http.filters;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Context for a single HTTP request passing through the filter chain.
 */
public class RequestContext {
    private final String method;
    private final URI uri;
    private final Map<String, String> headers;
    private String user;
    private final String remoteAddr;
    private long bytes;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public RequestContext(String method, URI uri, Map<String, String> headers, String remoteAddr) {
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.remoteAddr = remoteAddr;
    }

    public String getMethod() {
        return method;
    }

    public URI getUri() {
        return uri;
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public long getBytes() {
        return bytes;
    }

    public void setBytes(long bytes) {
        this.bytes = bytes;
    }
}
