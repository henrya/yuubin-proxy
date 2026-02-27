package com.yuubin.proxy.core.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.exceptions.AuthException;
import com.yuubin.proxy.core.exceptions.ConfigException;
import com.yuubin.proxy.entity.User;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Service for managing proxy user authentication.
 * Loads user credentials from an external YAML file and provides methods for
 * verification.
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * The current configuration properties.
     * Managed via AtomicReference to ensure thread-safe updates and visibility.
     */
    private final AtomicReference<YuubinProperties> properties = new AtomicReference<>();

    /**
     * The active user database (username -> password).
     * Managed via AtomicReference to allow atomic swapping of the entire user set
     * during a reload, ensuring zero-downtime updates.
     */
    private final AtomicReference<Map<String, String>> users = new AtomicReference<>(new ConcurrentHashMap<>());

    /**
     * Initializes the AuthService with the provided configuration.
     * The constructor calls {@link #reload()} immediately to fail fast if the
     * credentials source is misconfigured. Throwing from a constructor is
     * intentional here — a partially-constructed {@code AuthService} with no
     * users loaded would be a dangerous silent failure.
     * 
     * @param properties The root configuration properties.
     */
    @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
    public AuthService(YuubinProperties properties) {
        this.properties.set(properties);
        reload();
    }

    /**
     * Updates the internal configuration and reloads user data.
     * 
     * @param properties The new configuration properties.
     */
    public void updateProperties(YuubinProperties properties) {
        this.properties.set(properties);
        reload();
    }

    /**
     * Reloads the users from the configured path, directory, or environment
     * variable if authentication is enabled.
     * Performs an atomic swap of the 'users' map to ensure threads always see a
     * consistent state.
     */
    public void reload() {
        Map<String, String> newUsers = new ConcurrentHashMap<>();
        YuubinProperties props = properties.get();
        if (props.getAuth() != null && props.getAuth().isEnabled()) {
            if (props.getAuth().getUsersPath() != null) {
                loadUsersFromYaml(newUsers);
            }
            if (props.getAuth().getUsersDirectory() != null) {
                loadUsersFromDirectory(newUsers);
            }
            if (props.getAuth().getUsersEnv() != null) {
                loadUsersFromEnv(newUsers);
            }
        }
        this.users.set(newUsers);
    }

    /**
     * Loads user credentials from an environment variable.
     * Expects the format: "user1:pass1,user2:pass2".
     * 
     * @param map The map to populate.
     */
    private void loadUsersFromEnv(Map<String, String> map) {
        String envVarName = properties.get().getAuth().getUsersEnv();
        String envValue = getEnv(envVarName);

        if (envValue == null || envValue.isBlank()) {
            log.warn("Environment variable {} is not set or empty.", envVarName);
            return;
        }

        String[] pairs = envValue.split(",");
        int count = 0;
        for (String pair : pairs) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
                count++;
            } else {
                log.warn("Invalid user:pass pair in environment variable {}: {}", envVarName, pair);
            }
        }
        log.info("Loaded {} users from environment variable: {}", count, envVarName);
    }

    /**
     * Retrieves an environment variable. Overridable for testing.
     * 
     * @param name Name of the environment variable.
     * @return The variable's value.
     */
    String getEnv(String name) {
        return System.getenv(name);
    }

    /**
     * Loads user credentials from the YAML file specified in the configuration.
     * 
     * @param map The map to populate.
     */
    private void loadUsersFromYaml(Map<String, String> map) {
        String pathStr = properties.get().getAuth().getUsersPath();
        try (InputStream is = getInputStream(pathStr)) {
            if (is == null) {
                log.warn("Users YAML file not found: {}", pathStr);
                return;
            }

            Yaml yaml = new Yaml(new Constructor(UsersWrapper.class, new org.yaml.snakeyaml.LoaderOptions()));
            UsersWrapper wrapper = yaml.load(is);

            if (wrapper != null && wrapper.getUsers() != null) {
                for (User user : wrapper.getUsers()) {
                    if (user.getUsername() != null && user.getPassword() != null) {
                        map.put(user.getUsername(), user.getPassword());
                    }
                }
            }
            log.info("Loaded users from YAML: {}", pathStr);
        } catch (YAMLException e) {
            throw new ConfigException("Invalid YAML syntax in users file " + pathStr + ": " + e.getMessage());
        } catch (IOException e) {
            throw new AuthException("Failed to read user credentials from " + pathStr, e);
        } catch (Exception e) {
            if (e instanceof ConfigException configException) {
                throw configException;
            }
            throw new AuthException("Unexpected error loading users from " + pathStr, e);
        }
    }

    /**
     * Loads user credentials from a directory (e.g., Kubernetes ConfigMap mount).
     * Each file name is treated as a username, and its content as the password.
     * 
     * @param map The map to populate.
     */
    private void loadUsersFromDirectory(Map<String, String> map) {
        String dirPath = properties.get().getAuth().getUsersDirectory();
        Path path = Paths.get(dirPath);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            log.warn("Users directory not found or not a directory: {}", dirPath);
            return;
        }

        try (Stream<Path> stream = Files.list(path)) {
            stream.filter(Files::isRegularFile)
                    .forEach(file -> {
                        String username = file.getFileName().toString();
                        // Skip hidden files or Kubernetes symbolic links like ..data
                        if (username.startsWith(".")) {
                            return;
                        }

                        try {
                            String password = Files.readString(file).trim();
                            map.put(username, password);
                        } catch (IOException e) {
                            log.error("Failed to read password for user {} from {}: {}", username, file,
                                    e.getMessage());
                        }
                    });
            log.info("Loaded users from directory: {}", dirPath);
        } catch (IOException e) {
            throw new AuthException("Failed to list users directory: " + dirPath, e);
        }
    }

    /**
     * Wrapper class for YAML mapping of the users file.
     */
    public static class UsersWrapper {
        private List<User> users;

        @SuppressFBWarnings("EI_EXPOSE_REP")
        public List<User> getUsers() {
            return users;
        }

        @SuppressFBWarnings("EI_EXPOSE_REP2")
        public void setUsers(List<User> users) {
            this.users = users;
        }
    }

    /**
     * Resolves an input stream for the given path, supporting both filesystem and
     * classpath.
     */
    private InputStream getInputStream(String pathStr) throws IOException {
        if (pathStr.startsWith("classpath:")) {
            String cpPath = pathStr.substring(10);
            return getClass().getClassLoader().getResourceAsStream(cpPath);
        }
        Path path = Paths.get(pathStr);
        if (Files.exists(path)) {
            return Files.newInputStream(path);
        }
        return getClass().getClassLoader().getResourceAsStream(pathStr);
    }

    /**
     * Authenticates a user based on an HTTP Basic Authorization header.
     * 
     * @param authHeader The raw Authorization header (e.g., "Basic
     *                   YWRtaW46cGFzc3dvcmQ=").
     * @return true if authentication succeeds or is disabled, false otherwise.
     */
    public boolean authenticate(String authHeader) {
        if (!properties.get().getAuth().isEnabled()) {
            return true;
        }
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }

        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                return false;
            }

            return authenticate(parts[0], parts[1]);
        } catch (IllegalArgumentException e) {
            // Malformed Base64 in the Authorization header — treat as unauthenticated
            return false;
        }

    }

    /**
     * Verifies a username and password against the loaded users.
     * 
     * @param username The username to verify.
     * @param password The password to verify.
     * @return true if the credentials match or authentication is disabled.
     */
    public boolean authenticate(String username, String password) {
        if (!properties.get().getAuth().isEnabled()) {
            return true;
        }
        String storedPassword = users.get().get(username);
        if (storedPassword == null || password == null) {
            return false;
        }
        return MessageDigest.isEqual(
                storedPassword.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks if a user exists in the system.
     * 
     * @param username The username to check.
     * @return true if the user exists or authentication is disabled.
     */
    public boolean userExists(String username) {
        if (!properties.get().getAuth().isEnabled()) {
            return true;
        }
        return username != null && users.get().containsKey(username);
    }

    /**
     * Programmatically adds a user to the active session.
     * 
     * @param username The username to add.
     * @param password The password for the user.
     */
    public void addUser(String username, String password) {
        users.get().put(username, password);
    }
}
