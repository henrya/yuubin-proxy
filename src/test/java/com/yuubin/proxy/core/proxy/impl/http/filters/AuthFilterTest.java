package com.yuubin.proxy.core.proxy.impl.http.filters;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.core.constants.HeaderConstants;
import com.yuubin.proxy.core.services.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AuthFilterTest {

    private AuthFilter authFilter;
    private ProxyServerConfig config;
    private AuthService authService;
    private RequestContext context;
    private ByteArrayOutputStream out;
    private Map<String, String> headers;

    @BeforeEach
    void setUp() throws Exception {
        config = new ProxyServerConfig();
        authService = Mockito.mock(AuthService.class);
        authFilter = new AuthFilter(config, authService);
        out = new ByteArrayOutputStream();
        
        headers = new HashMap<>();
        context = new RequestContext("GET", new URI("http://localhost"), headers, "127.0.0.1");
    }

    @Test
    void preHandle_authDisabled_returnsTrue() throws IOException {
        config.setAuthEnabled(false);
        assertThat(authFilter.preHandle(context, out)).isTrue();
    }

    @Test
    void preHandle_validAuth_returnsTrueAndSetsUser() throws IOException {
        config.setAuthEnabled(true);
        String user = "admin";
        String pass = "pass";
        String header = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        headers.put(HeaderConstants.PROXY_AUTHORIZATION.getValue(), header);
        
        when(authService.authenticate(header)).thenReturn(true);
        
        assertThat(authFilter.preHandle(context, out)).isTrue();
        assertThat(context.getUser()).isEqualTo(user);
    }

    @Test
    void preHandle_invalidAuth_returnsFalseAndWrites407() throws IOException {
        config.setAuthEnabled(true);
        headers.put(HeaderConstants.PROXY_AUTHORIZATION.getValue(), "Basic wrong");
        
        when(authService.authenticate(anyString())).thenReturn(false);
        
        assertThat(authFilter.preHandle(context, out)).isFalse();
        assertThat(out.toString()).contains("407 Proxy Authentication Required");
    }

    @Test
    void preHandle_malformedAuthHeader_handlesGracefully() throws IOException {
        config.setAuthEnabled(true);
        headers.put(HeaderConstants.PROXY_AUTHORIZATION.getValue(), "Basic @@@"); // Bad base64
        
        when(authService.authenticate(anyString())).thenReturn(true); // AuthService might still return true if disabled internally, but extractUser should not crash
        
        assertThat(authFilter.preHandle(context, out)).isTrue();
        assertThat(context.getUser()).isNull();
    }
}
