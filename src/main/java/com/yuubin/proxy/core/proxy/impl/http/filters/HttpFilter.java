package com.yuubin.proxy.core.proxy.impl.http.filters;

import java.io.IOException;
import java.io.OutputStream;
import com.yuubin.proxy.core.exceptions.ProxyException;

/**
 * Filter for intercepting and potentially modifying HTTP requests and
 * responses.
 */
public interface HttpFilter {
    /**
     * Called before the request is sent to the target server.
     *
     * @return true to continue to next filter, false to abort request.
     */
    boolean preHandle(RequestContext context, OutputStream clientOut) throws IOException, ProxyException;

    /**
     * Called after the response is received but before it's sent to the client.
     */
    default void postHandle(RequestContext context, int statusCode) {
    }
}
