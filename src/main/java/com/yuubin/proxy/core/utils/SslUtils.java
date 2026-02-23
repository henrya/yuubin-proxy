package com.yuubin.proxy.core.utils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;

import com.yuubin.proxy.config.ProxyServerConfig;
import com.yuubin.proxy.config.YuubinProperties;

/**
 * Provides utility methods for creating SSL contexts and server socket
 * factories.
 */
public class SslUtils {

    private SslUtils() {
        // Utility class
    }

    /**
     * Creates an {@link SSLServerSocketFactory} based on the proxy configuration.
     * 
     * @param config      The proxy server configuration.
     * @param globalProps Global properties (for certificatesPath).
     * @return An initialized SSL server socket factory.
     * @throws IOException              If the keystore file cannot be read.
     * @throws GeneralSecurityException If the SSL context or keystore cannot be
     *                                  initialized.
     */
    public static SSLServerSocketFactory createSslFactory(ProxyServerConfig config, YuubinProperties globalProps)
            throws IOException, GeneralSecurityException {
        String ksPathStr = config.getKeystorePath();
        if (ksPathStr == null || ksPathStr.isEmpty()) {
            throw new IllegalArgumentException(
                    "Keystore path must be specified when TLS is enabled for " + config.getName());
        }

        Path ksPath = Paths.get(ksPathStr);
        if (!ksPath.isAbsolute()) {
            ksPath = Paths.get(globalProps.getCertificatesPath(), ksPathStr);
        }

        char[] password = config.getKeystorePassword() != null ? config.getKeystorePassword().toCharArray()
                : new char[0];

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(ksPath.toFile())) {
            ks.load(is, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext.getServerSocketFactory();
    }
}
