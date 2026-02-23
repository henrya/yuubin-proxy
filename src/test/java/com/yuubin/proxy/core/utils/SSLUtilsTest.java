package com.yuubin.proxy.core.utils;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SslUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void createSslFactory_throwsException_whenKeystorePathMissing() {
        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        YuubinProperties props = new YuubinProperties();
        
        assertThatThrownBy(() -> SslUtils.createSslFactory(config, props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Keystore path must be specified");
    }

    @Test
    void createSslFactory_throwsException_whenFileNotFound() {
        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("nonexistent.p12");
        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(tempDir.toString());
        
        assertThatThrownBy(() -> SslUtils.createSslFactory(config, props))
                .isInstanceOf(java.io.FileNotFoundException.class);
    }

    @Test
    void createSslFactory_success_withValidKeystore() throws Exception {
        // Generate a temporary keystore for testing
        File ksFile = tempDir.resolve("test.p12").toFile();
        String password = "password";
        
        // Execute keytool to create a self-signed cert in a PKCS12 keystore
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keystore", ksFile.getAbsolutePath(),
                "-storepass", password,
                "-keypass", password,
                "-dname", "CN=Test",
                "-validity", "1",
                "-storetype", "PKCS12"
        );
        Process p = pb.start();
        try {
            boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(finished).isTrue();
            assertThat(p.exitValue()).isEqualTo(0);
        } finally {
            p.destroy();
        }

        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("test.p12");
        config.setKeystorePassword(password);
        
        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(tempDir.toString());

        SSLServerSocketFactory factory = SslUtils.createSslFactory(config, props);
        assertThat(factory).isNotNull();
    }
}
