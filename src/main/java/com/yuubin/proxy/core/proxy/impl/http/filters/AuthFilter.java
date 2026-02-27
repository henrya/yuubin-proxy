package com.yuubin.proxy.core.proxy.impl.http.filters;

import com.yuubin.proxy.core.exceptions.ProxyException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.core.constants.HeaderConstants;
import com.yuubin.proxy.core.services.AuthService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Filter that enforces Proxy Authentication (RFC 7235).
 */
public class AuthFilter implements HttpFilter {
    private final ProxyServerConfig config;
    private final AuthService authService;

    /**
     * Initializes the AuthFilter with configuration and authentication service.
     * 
     * @param config      The server configuration.
     * @param authService The authentication provider.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public AuthFilter(ProxyServerConfig config, AuthService authService) {
        this.config = config;
        this.authService = authService;
    }

    @Override
    public boolean preHandle(RequestContext context, OutputStream out) throws IOException, ProxyException {
        if (!config.isAuthEnabled()) {
            return true;
        }

        String authHeader = context.getHeaders().get(HeaderConstants.PROXY_AUTHORIZATION.getValue());
        if (authService.authenticate(authHeader)) {
            context.setUser(extractUser(authHeader));
            return true;
        }

        // Return 407 Proxy Authentication Required
        out.write("HTTP/1.1 407 Proxy Authentication Required\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write("Proxy-Authenticate: Basic realm=\"YuubinProxy\"\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return false;
    }

    private String extractUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            int idx = decoded.indexOf(":");
            if (idx != -1) {
                return decoded.substring(0, idx);
            }
        } catch (Exception ignored) {
            // ignore the exception
        }
        return null;
    }
}
