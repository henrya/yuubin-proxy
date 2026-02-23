package com.yuubin.proxy.core.proxy;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.impl.http.HttpProxyServer;
import com.yuubin.proxy.core.proxy.impl.socks.Socks4ProxyServer;
import com.yuubin.proxy.core.proxy.impl.socks.Socks5ProxyServer;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProxyServerFactoryTest {

    @Test
    void create_returnsCorrectServerType() {
        AuthService auth = mock(AuthService.class);
        LoggingService logging = mock(LoggingService.class);
        MeterRegistry registry = mock(MeterRegistry.class);
        YuubinProperties props = new YuubinProperties();
        ProxyServerFactory factory = new ProxyServerFactory(auth, logging, registry, props);

        ProxyServerConfig httpCfg = new ProxyServerConfig();
        httpCfg.setType("HTTP");
        ProxyServer httpSrv = factory.create(httpCfg);
        assertThat(httpSrv).isInstanceOf(HttpProxyServer.class);
        httpSrv.stop();

        ProxyServerConfig s4Cfg = new ProxyServerConfig();
        s4Cfg.setType("SOCKS4");
        ProxyServer s4Srv = factory.create(s4Cfg);
        assertThat(s4Srv).isInstanceOf(Socks4ProxyServer.class);
        s4Srv.stop();

        ProxyServerConfig s5Cfg = new ProxyServerConfig();
        s5Cfg.setType("SOCKS5");
        ProxyServer s5Srv = factory.create(s5Cfg);
        assertThat(s5Srv).isInstanceOf(Socks5ProxyServer.class);
        s5Srv.stop();
    }
}