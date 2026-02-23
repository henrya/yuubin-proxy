package com.yuubin.proxy;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.exceptions.ProxyException;
import com.yuubin.proxy.core.utils.IoUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Behavioural tests for utilities and factory edge cases.
 */
class ProxyUtilsBehaviourTest {

    @Test
    @DisplayName("ProxyServerFactory throws ProxyException for unknown proxy type")
    void factory_rejectsUnknownProxyType() {
        AuthService auth = mock(AuthService.class);
        LoggingService logging = mock(LoggingService.class);
        ProxyServerFactory factory = new ProxyServerFactory(auth, logging, new SimpleMeterRegistry(),
                new YuubinProperties());

        ProxyServerConfig cfg = new ProxyServerConfig();
        cfg.setType("UNKNOWN");

        assertThatThrownBy(() -> factory.create(cfg))
                .isInstanceOf(ProxyException.class)
                .hasMessageContaining("Unsupported proxy type");
    }

    @Test
    @DisplayName("IoUtils.readLine reads multiple lines and returns null at EOF")
    void readLine_readsAllLinesAndReturnNullAtEof() throws IOException {
        String input = "line1\nline2\nline3";
        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        assertThat(IoUtils.readLine(bais)).isEqualTo("line1");
        assertThat(IoUtils.readLine(bais)).isEqualTo("line2");
        assertThat(IoUtils.readLine(bais)).isEqualTo("line3");
        assertThat(IoUtils.readLine(bais)).isNull();
    }

    @Test
    @DisplayName("IoUtils.readLine throws IOException when line exceeds the maximum allowed length")
    void readLine_throwsWhenLineTooLong() {
        byte[] large = new byte[100];
        ByteArrayInputStream bais = new ByteArrayInputStream(large);

        assertThatThrownBy(() -> IoUtils.readLine(bais, 50))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Line length exceeds maximum allowed length");
    }

    @Test
    @DisplayName("IoUtils.closeQuietly does not throw when given a null resource")
    void closeQuietly_toleratesNull() {
        assertThatCode(() -> IoUtils.closeQuietly(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IoUtils.closeQuietly swallows exceptions from the closed resource")
    void closeQuietly_swallowsCloseExceptions() {
        AutoCloseable bad = () -> {
            throw new Exception("fail");
        };
        assertThatCode(() -> IoUtils.closeQuietly(bad)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Rule health management correctly excludes unhealthy targets from selection")
    void rule_excludesUnhealthyTargetsAndRestoresThemWhenHealthy() {
        com.yuubin.proxy.entity.Rule rule = new com.yuubin.proxy.entity.Rule();
        rule.setTargets(java.util.List.of("t1", "t2"));
        rule.setHealthCheckPath("/health");

        rule.markUnhealthy("t1");
        assertThat(rule.getTarget(null)).isEqualTo("t2");

        rule.markHealthy("t1");
        assertThat(rule.getTarget(null)).isIn("t1", "t2");
    }

    @Test
    @DisplayName("Rule rate limiting blocks a second request when the burst is exhausted")
    void rule_rateLimiting_blocksRequestsAfterBurstExhausted() {
        com.yuubin.proxy.entity.Rule rule = new com.yuubin.proxy.entity.Rule();
        rule.setRateLimit(1.0);
        rule.setBurst(1);

        assertThat(rule.allowRequest("1.1.1.1")).isTrue();
        assertThat(rule.allowRequest("1.1.1.1")).isFalse();
    }
}
