package com.yuubin.proxy.config;

/**
 * Configuration for the logging service.
 */
public class LoggingConfig {
    /** Logging format (Apache-style placeholders like %h, %r, %s). */
    private String format = "%h %l %u %t \"%r\" %>s %b";

    /** Whether to log full response details (useful for debugging). */
    private boolean logResponse = false;

    /** Whether to enable file logging. */
    private boolean fileEnabled = false;

    /** Path to the log file directory. */
    private String filePath = "logs";

    /** Name of the log file. */
    private String fileName = "yuubin-proxy.log";

    /** Rotation type: DAILY, WEEKLY, MONTHLY, SIZE. */
    private String rotation = "DAILY";

    /**
     * Max size of the log file before rotation (e.g., "10MB"). Only used if
     * rotation is SIZE.
     */
    private String maxSize = "10MB";

    /** Max number of archived log files to keep. */
    private int maxHistory = 30;

    /**
     * Gets the logging format (Apache-style placeholders like %h, %r, %s).
     * 
     * @return the log format string.
     */
    public String getFormat() {
        return format;
    }

    /**
     * Sets the logging format (Apache-style placeholders like %h, %r, %s).
     * 
     * @param format the log format string.
     */
    public void setFormat(String format) {
        this.format = format;
    }

    /**
     * Checks if full response details logging is enabled.
     * 
     * @return true if enabled, false otherwise.
     */
    public boolean isLogResponse() {
        return logResponse;
    }

    /**
     * Sets whether to log full response details.
     * 
     * @param logResponse true to enable, false to disable.
     */
    public void setLogResponse(boolean logResponse) {
        this.logResponse = logResponse;
    }

    /**
     * Checks if file logging is enabled.
     * 
     * @return true if enabled, false otherwise.
     */
    public boolean isFileEnabled() {
        return fileEnabled;
    }

    /**
     * Sets whether to enable file logging.
     * 
     * @param fileEnabled true to enable, false to disable.
     */
    public void setFileEnabled(boolean fileEnabled) {
        this.fileEnabled = fileEnabled;
    }

    /**
     * Gets the path to the log file directory.
     * 
     * @return the log file path.
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * Sets the path to the log file directory.
     * 
     * @param filePath the log file path.
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Gets the name of the log file.
     * 
     * @return the log file name.
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Sets the name of the log file.
     * 
     * @param fileName the log file name.
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Gets the rotation type (DAILY, WEEKLY, MONTHLY, SIZE).
     * 
     * @return the rotation type string.
     */
    public String getRotation() {
        return rotation;
    }

    /**
     * Sets the rotation type (DAILY, WEEKLY, MONTHLY, SIZE).
     * 
     * @param rotation the rotation type string.
     */
    public void setRotation(String rotation) {
        this.rotation = rotation;
    }

    /**
     * Gets the max size of the log file before rotation (e.g., "10MB").
     * 
     * @return the max size string.
     */
    public String getMaxSize() {
        return maxSize;
    }

    /**
     * Sets the max size of the log file before rotation (e.g., "10MB").
     * 
     * @param maxSize the max size string.
     */
    public void setMaxSize(String maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * Gets the max number of archived log files to keep.
     * 
     * @return the max history value.
     */
    public int getMaxHistory() {
        return maxHistory;
    }

    /**
     * Sets the max number of archived log files to keep.
     * 
     * @param maxHistory the max history value.
     */
    public void setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
    }
}
