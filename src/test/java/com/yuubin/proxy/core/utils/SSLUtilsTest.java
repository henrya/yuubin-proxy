package com.yuubin.proxy.core.utils;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SslUtilsTest {

    @TempDir
    Path tempDir;

    // ── Existing tests ───────────────────────────────────────────────────────

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
        generateKeystore("test.p12", "password");

        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("test.p12");
        config.setKeystorePassword("password");

        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(tempDir.toString());

        SSLServerSocketFactory factory = SslUtils.createSslFactory(config, props);
        assertThat(factory).isNotNull();
    }

    // ── Gap #1: Empty string keystore path ───────────────────────────────────

    @Test
    void createSslFactory_throwsException_whenKeystorePathEmpty() {
        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("");
        YuubinProperties props = new YuubinProperties();

        assertThatThrownBy(() -> SslUtils.createSslFactory(config, props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Keystore path must be specified");
    }

    // ── Gap #2: Wrong keystore password ──────────────────────────────────────

    @Test
    void createSslFactory_throwsException_whenPasswordIncorrect() throws Exception {
        generateKeystore("password-test.p12", "correct-password");

        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("password-test.p12");
        config.setKeystorePassword("wrong-password");

        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(tempDir.toString());

        assertThatThrownBy(() -> SslUtils.createSslFactory(config, props))
                .isInstanceOf(IOException.class);
    }

    // ── Gap #3: Absolute vs relative path resolution ─────────────────────────

    @Test
    void createSslFactory_resolvesRelativePath_againstCertificatesPath() throws Exception {
        // Create a subdirectory as certificatesPath
        Path certDir = tempDir.resolve("certs");
        Files.createDirectories(certDir);
        generateKeystoreAt(certDir.resolve("relative.p12").toFile(), "password");

        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("relative.p12"); // Relative — should resolve against certDir
        config.setKeystorePassword("password");

        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(certDir.toString());

        SSLServerSocketFactory factory = SslUtils.createSslFactory(config, props);
        assertThat(factory).isNotNull();
    }

    @Test
    void createSslFactory_usesAbsolutePath_directly() throws Exception {
        File ksFile = generateKeystore("absolute.p12", "password");

        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath(ksFile.getAbsolutePath()); // Absolute — should NOT prepend certificatesPath
        config.setKeystorePassword("password");

        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath("/nonexistent/directory"); // Would fail if used

        SSLServerSocketFactory factory = SslUtils.createSslFactory(config, props);
        assertThat(factory).isNotNull();
    }

    // ── Gap #4: Corrupt/invalid keystore file ────────────────────────────────

    @Test
    void createSslFactory_throwsException_whenKeystoreCorrupt() throws Exception {
        // Write garbage bytes to a file
        File corruptFile = tempDir.resolve("corrupt.p12").toFile();
        Files.write(corruptFile.toPath(), "this is not a valid keystore".getBytes());

        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("corrupt.p12");
        config.setKeystorePassword("password");

        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(tempDir.toString());

        assertThatThrownBy(() -> SslUtils.createSslFactory(config, props))
                .isInstanceOf(IOException.class);
    }

    // ── Gap #7: Null keystore password ───────────────────────────────────────

    @Test
    void createSslFactory_handlesNullPassword_withUnprotectedKeystore() throws Exception {
        // Generate a keystore with an empty password (keytool requires a password,
        // but PKCS12 supports loading with empty char array)
        generateKeystore("null-pw.p12", "temppass");

        ProxyServerConfig config = new ProxyServerConfig();
        config.setName("test");
        config.setKeystorePath("null-pw.p12");
        config.setKeystorePassword(null); // Null password — code uses new char[0]

        YuubinProperties props = new YuubinProperties();
        props.setCertificatesPath(tempDir.toString());

        // With a null password on a password-protected keystore, this should throw.
        // This verifies the null → empty char[] fallback path is exercised.
        assertThatThrownBy(() -> SslUtils.createSslFactory(config, props))
                .isInstanceOf(IOException.class);
    }

    // ── Helper: generate PKCS12 keystore via keytool ─────────────────────────

    private File generateKeystore(String filename, String password) throws Exception {
        File ksFile = tempDir.resolve(filename).toFile();
        return generateKeystoreAt(ksFile, password);
    }

    private File generateKeystoreAt(File ksFile, String password) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-keystore", ksFile.getAbsolutePath(),
                "-storepass", password,
                "-keypass", password,
                "-dname", "CN=Test",
                "-validity", "1",
                "-storetype", "PKCS12");
        Process p = pb.start();
        try {
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            assertThat(finished).as("keytool should finish in 10s").isTrue();
            assertThat(p.exitValue()).as("keytool exit code").isEqualTo(0);
        } finally {
            p.destroy();
        }
        return ksFile;
    }
}
