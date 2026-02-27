package com.yuubin.proxy.core.services;

import com.yuubin.proxy.config.AuthConfig;
import com.yuubin.proxy.config.YuubinProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.stream.Stream;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.yuubin.proxy.core.exceptions.ConfigException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuthServiceTest {
    private AuthService authService;
    private YuubinProperties properties;

    @BeforeEach
    void setUp() {
        properties = new YuubinProperties();
        AuthConfig authConfig = new AuthConfig();
        authConfig.setEnabled(true);
        properties.setAuth(authConfig);
        authService = new AuthService(properties);
    }

    @Test
    void authenticate_withValidBasicAuth_returnsTrue() {
        authService.addUser("admin", "pass");
        String header = "Basic " + Base64.getEncoder().encodeToString("admin:pass".getBytes());
        assertThat(authService.authenticate(header)).isTrue();
    }

    @Test
    void authenticate_withInvalidBasicAuth_returnsFalse() {
        authService.addUser("admin", "pass");
        String header = "Basic " + Base64.getEncoder().encodeToString("admin:wrong".getBytes());
        assertThat(authService.authenticate(header)).isFalse();
    }

    @Test
    void authenticate_disabledAuth_returnsTrue() {
        properties.getAuth().setEnabled(false);
        assertThat(authService.authenticate(null)).isTrue();
    }

    @Test
    void authenticate_handlesInvalidHeaders() {
        assertThat(authService.authenticate(null)).isFalse();
        assertThat(authService.authenticate("Invalid")).isFalse();
        assertThat(authService.authenticate("Basic " + Base64.getEncoder().encodeToString("bad".getBytes()))).isFalse();
    }

    @Test
    void reload_withFileResource_loadsUsers() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("users", ".yml");
        java.nio.file.Files.writeString(tempFile, "users:\n  - username: fileuser\n    password: filepass\n");

        properties.getAuth().setEnabled(true);
        properties.getAuth().setUsersPath(tempFile.toAbsolutePath().toString());

        authService.reload();

        String header = "Basic " + Base64.getEncoder().encodeToString("fileuser:filepass".getBytes());
        assertThat(authService.authenticate(header)).isTrue();

        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void reload_withDirectoryResource_loadsUsers() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("users-dir");
        java.nio.file.Files.writeString(tempDir.resolve("user1"), "pass1");
        java.nio.file.Files.writeString(tempDir.resolve("user2"), "pass2\n"); // test trim
        java.nio.file.Files.createFile(tempDir.resolve(".hidden")); // should be ignored

        properties.getAuth().setEnabled(true);
        properties.getAuth().setUsersDirectory(tempDir.toAbsolutePath().toString());
        properties.getAuth().setUsersPath(null);

        authService.reload();

        assertThat(authService.authenticate("user1", "pass1")).isTrue();
        assertThat(authService.authenticate("user2", "pass2")).isTrue();
        assertThat(authService.userExists(".hidden")).isFalse();

        // Cleanup
        Comparator<Path> comparator = Comparator.reverseOrder();
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(comparator).forEach(path -> {
                try {
                    java.nio.file.Files.delete(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void reload_withEnvVariable_loadsUsers() {
        String envVar = "MY_PROXY_USERS";
        properties.getAuth().setEnabled(true);
        properties.getAuth().setUsersEnv(envVar);
        properties.getAuth().setUsersPath(null);
        properties.getAuth().setUsersDirectory(null);

        AuthService spyService = org.mockito.Mockito.spy(authService);
        org.mockito.Mockito.doReturn("envuser:envpass, envuser2 : envpass2").when(spyService).getEnv(envVar);

        spyService.reload();

        assertThat(spyService.authenticate("envuser", "envpass")).isTrue();
        assertThat(spyService.authenticate("envuser2", "envpass2")).isTrue();
    }

    @Test
    void reload_clearsUsersWhenDisabled() {
        properties.getAuth().setEnabled(false);
        authService.reload();
        assertThat(authService.authenticate("any", "any")).isTrue();
    }

    @Test
    void authenticate_handlesMalformedBase64() {
        assertThat(authService.authenticate("Basic @@@")).isFalse();
    }

    @Test
    void reload_withMalformedYaml_throwsConfigException() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("users-bad", ".yml");
        // Inject fatal YAML structural errors (e.g., duplicated exact keys, bad
        // indents, broken arrays)
        java.nio.file.Files.writeString(tempFile,
                "users:\n" +
                        "  - username: valid\n" +
                        "  - [broken_yaml_array\n" +
                        "  wrong_indentation");

        properties.getAuth().setUsersPath(tempFile.toAbsolutePath().toString());

        assertThatCode(() -> authService.reload())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Invalid YAML syntax in users file");

        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void loadUsers_warnsOnMissingDirectory() {
        properties.getAuth().setEnabled(true);
        properties.getAuth().setUsersDirectory("/non/existent/dir/yuubin");
        authService.reload();
        assertThat(authService.userExists("any")).isFalse();
    }

    @Test
    void loadUsers_warnsOnNotADirectory() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("not-a-dir", ".txt");
        properties.getAuth().setEnabled(true);
        properties.getAuth().setUsersDirectory(tempFile.toAbsolutePath().toString());
        authService.reload();
        assertThat(authService.userExists("any")).isFalse();
        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void updateProperties_reloadsUsers() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("users-update", ".yml");
        java.nio.file.Files.writeString(tempFile, "users:\n  - username: user1\n    password: p1\n");

        YuubinProperties newProps = new YuubinProperties();
        AuthConfig ac = new AuthConfig();
        ac.setEnabled(true);
        ac.setUsersPath(tempFile.toAbsolutePath().toString());
        newProps.setAuth(ac);

        authService.updateProperties(newProps);
        assertThat(authService.userExists("user1")).isTrue();

        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void authenticate_withEmptyUsername_returnsFalse() {
        authService.addUser("admin", "pass");
        String header = "Basic " + Base64.getEncoder().encodeToString(":pass".getBytes());
        assertThat(authService.authenticate(header)).isFalse();
    }

    @Test
    void authenticate_withEmptyPassword_returnsFalse() {
        authService.addUser("admin", "pass");
        String header = "Basic " + Base64.getEncoder().encodeToString("admin:".getBytes());
        assertThat(authService.authenticate(header)).isFalse();
    }

    @Test
    void authenticate_withBothEmpty_returnsFalse() {
        String header = "Basic " + Base64.getEncoder().encodeToString(":".getBytes());
        assertThat(authService.authenticate(header)).isFalse();
    }

    @Test
    void authenticate_concurrentReloadAndAuthenticate_neverThrows() throws Exception {
        authService.addUser("admin", "pass");
        String header = "Basic " + Base64.getEncoder().encodeToString("admin:pass".getBytes());

        // Hammer authenticate from multiple threads while reload() runs concurrently
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < 200; j++) {
                    authService.authenticate(header); // must never throw
                }
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            start.await();
            for (int j = 0; j < 50; j++) {
                authService.reload();
            }
            return null;
        }));

        start.countDown();
        assertThatCode(() -> {
            for (var f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }).doesNotThrowAnyException();
        pool.shutdown();
    }

}
