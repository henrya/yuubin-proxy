package com.yuubin.proxy.core.proxy;

import com.yuubin.proxy.config.YuubinProperties;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class ProxyManagerTest {

    @Test
    void startServers_withNoProxies_logsWarning() {
        YuubinProperties props = new YuubinProperties();
        props.setProxies(null);
        ProxyServerFactory factory = mock(ProxyServerFactory.class);
        ProxyManager manager = new ProxyManager(props, factory);
        manager.startServers();
        verifyNoInteractions(factory);
    }
}
