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
     * Path to a directory containing user credentials (e.g., a Kubernetes ConfigMap mount).
     * Each filename is a username, and its content is the password.
     */
    private String usersDirectory;

    /**
     * Name of an environment variable containing user credentials in the format "user1:pass1,user2:pass2".
     */
    private String usersEnv;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsersPath() {
        return usersPath;
    }

    public void setUsersPath(String usersPath) {
        this.usersPath = usersPath;
    }

    public String getUsersDirectory() {
        return usersDirectory;
    }

    public void setUsersDirectory(String usersDirectory) {
        this.usersDirectory = usersDirectory;
    }

    public String getUsersEnv() {
        return usersEnv;
    }

    public void setUsersEnv(String usersEnv) {
        this.usersEnv = usersEnv;
    }
}
