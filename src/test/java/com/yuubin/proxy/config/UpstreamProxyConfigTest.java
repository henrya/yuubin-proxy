package com.yuubin.proxy.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UpstreamProxyConfigTest {

    @Test
    void testGettersAndSetters() {
        UpstreamProxyConfig config = new UpstreamProxyConfig();
        config.setHost("localhost");
        config.setPort(8080);
        config.setType("SOCKS5");
        config.setUsername("user");
        config.setPassword("pass");

        assertThat(config.getHost()).isEqualTo("localhost");
        assertThat(config.getPort()).isEqualTo(8080);
        assertThat(config.getType()).isEqualTo("SOCKS5");
        assertThat(config.getUsername()).isEqualTo("user");
        assertThat(config.getPassword()).isEqualTo("pass");
    }

    @Test
    void testEqualsAndHashCode() {
        UpstreamProxyConfig c1 = new UpstreamProxyConfig();
        c1.setHost("localhost");
        c1.setPort(8080);
        
        UpstreamProxyConfig c2 = new UpstreamProxyConfig();
        c2.setHost("localhost");
        c2.setPort(8080);

        UpstreamProxyConfig c3 = new UpstreamProxyConfig();
        c3.setHost("other");
        c3.setPort(9090);

        assertThat(c1).isEqualTo(c2);
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        assertThat(c1).isNotEqualTo(c3);
        assertThat(c1).isNotEqualTo(null);
        assertThat(c1).isNotEqualTo(new Object());
    }
}
