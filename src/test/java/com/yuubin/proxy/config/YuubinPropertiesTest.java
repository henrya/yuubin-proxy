package com.yuubin.proxy.config;

import com.yuubin.proxy.entity.Rule;
import com.yuubin.proxy.entity.User;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class YuubinPropertiesTest {
    @Test
    void testGettersAndSetters() {
        Rule rule = new Rule();
        rule.setPath("/test");
        rule.setTarget("http://target");
        rule.setHeaders(Map.of("K", "V"));
        rule.setReverse(true);
        
        assertThat(rule.getPath()).isEqualTo("/test");
        assertThat(rule.getTarget()).isEqualTo("http://target");
        assertThat(rule.getHeaders()).containsKey("K");
        assertThat(rule.isReverse()).isTrue();
        
        LoggingConfig log = new LoggingConfig();
        log.setFormat("%h");
        log.setLogResponse(true);
        assertThat(log.getFormat()).isEqualTo("%h");
        assertThat(log.isLogResponse()).isTrue();

        AuthConfig auth = new AuthConfig();
        auth.setEnabled(true);
        auth.setUsersPath("p");
        auth.setUsersDirectory("d");
        assertThat(auth.isEnabled()).isTrue();
        assertThat(auth.getUsersPath()).isEqualTo("p");
        assertThat(auth.getUsersDirectory()).isEqualTo("d");

        ProxyServerConfig psc = new ProxyServerConfig();
        psc.setName("n");
        psc.setPort(80);
        psc.setTimeout(1);
        psc.setMaxRedirects(2);
        psc.setMaxConnections(3);
        psc.setBindAddress("127.0.0.1");
        psc.setKeepAlive(false);
        psc.setAuthEnabled(true);
        
        assertThat(psc.getName()).isEqualTo("n");
        assertThat(psc.getPort()).isEqualTo(80);
        assertThat(psc.getTimeout()).isEqualTo(1);
        assertThat(psc.getMaxRedirects()).isEqualTo(2);
        assertThat(psc.getMaxConnections()).isEqualTo(3);
        assertThat(psc.getBindAddress()).isEqualTo("127.0.0.1");
        assertThat(psc.isKeepAlive()).isFalse();
        assertThat(psc.isAuthEnabled()).isTrue();

        YuubinProperties props = new YuubinProperties();
        props.setProxies(java.util.List.of(psc));
        props.setAuth(auth);
        props.setLogging(log);
        assertThat(props.getProxies()).hasSize(1);
        assertThat(props.getAuth()).isEqualTo(auth);
        assertThat(props.getLogging()).isEqualTo(log);
    }

    @Test
    void testEqualsAndHashCode() {
        User u1 = new User(); u1.setUsername("a"); u1.setPassword("b");
        User u2 = new User(); u2.setUsername("a"); u2.setPassword("b");
        User u3 = new User(); u3.setUsername("x"); u3.setPassword("y");
        
        assertThat(u1).isEqualTo(u2).isNotEqualTo(u3);
        assertThat(u1.hashCode()).isEqualTo(u2.hashCode());

        Rule r1 = new Rule(); r1.setPath("/p");
        Rule r2 = new Rule(); r2.setPath("/p");
        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());

        ProxyServerConfig c1 = new ProxyServerConfig(); c1.setName("n");
        ProxyServerConfig c2 = new ProxyServerConfig(); c2.setName("n");
        assertThat(c1).isEqualTo(c2);
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
    }
}
