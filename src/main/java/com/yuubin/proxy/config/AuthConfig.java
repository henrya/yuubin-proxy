package com.yuubin.proxy.config;

/**
 * Configuration for global authentication settings.
 */
public class AuthConfig {
    /** Whether global authentication is enabled. */
    private boolean enabled = false;

    /** Path to the YAML file containing user credentials. */
    private String usersPath;

    /**
     * Path to a directory containing user credentials (e.g., a Kubernetes ConfigMap
     * mount).
     * Each filename is a username, and its content is the password.
     */
    private String usersDirectory;

    /**
     * Name of an environment variable containing user credentials in the format
     * "user1:pass1,user2:pass2".
     */
    private String usersEnv;

    /**
     * Checks if global authentication is enabled.
     * 
     * @return true if enabled, false otherwise.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether global authentication is enabled.
     * 
     * @param enabled true to enable, false to disable.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Gets the path to the YAML file containing user credentials.
     * 
     * @return the users path.
     */
    public String getUsersPath() {
        return usersPath;
    }

    /**
     * Sets the path to the YAML file containing user credentials.
     * 
     * @param usersPath the users path.
     */
    public void setUsersPath(String usersPath) {
        this.usersPath = usersPath;
    }

    /**
     * Gets the path to a directory containing user credentials.
     * 
     * @return the users directory path.
     */
    public String getUsersDirectory() {
        return usersDirectory;
    }

    /**
     * Sets the path to a directory containing user credentials.
     * 
     * @param usersDirectory the users directory path.
     */
    public void setUsersDirectory(String usersDirectory) {
        this.usersDirectory = usersDirectory;
    }

    /**
     * Gets the name of an environment variable containing user credentials.
     * 
     * @return the users environment variable name.
     */
    public String getUsersEnv() {
        return usersEnv;
    }

    /**
     * Sets the name of an environment variable containing user credentials.
     * 
     * @param usersEnv the users environment variable name.
     */
    public void setUsersEnv(String usersEnv) {
        this.usersEnv = usersEnv;
    }
}
