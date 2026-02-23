package com.yuubin.proxy.core.proxy.impl.http.filters;

import java.io.OutputStream;

import com.yuubin.proxy.core.services.LoggingService;

/**
 * Filter that logs HTTP requests in an Apache-style format.
 */
public class LoggingFilter implements HttpFilter {
    private final LoggingService loggingService;

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP2")
    public LoggingFilter(LoggingService loggingService) {
        this.loggingService = loggingService;
    }

    @Override
    public boolean preHandle(RequestContext context, OutputStream clientOut)
            throws java.io.IOException, com.yuubin.proxy.core.exceptions.ProxyException {
        return true; // No pre-handling needed for logging
    }

    @Override
    public void postHandle(RequestContext context, int statusCode) {
        loggingService.logRequest(
                context.getRemoteAddr(),
                context.getUser(),
                context.getMethod(),
                context.getUri().toString(),
                statusCode,
                context.getBytes());
    }
}
