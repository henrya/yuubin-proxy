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

    /** Max size of the log file before rotation (e.g., "10MB"). Only used if rotation is SIZE. */
    private String maxSize = "10MB";

    /** Max number of archived log files to keep. */
    private int maxHistory = 30;

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public boolean isLogResponse() {
        return logResponse;
    }

    public void setLogResponse(boolean logResponse) {
        this.logResponse = logResponse;
    }

    public boolean isFileEnabled() {
        return fileEnabled;
    }

    public void setFileEnabled(boolean fileEnabled) {
        this.fileEnabled = fileEnabled;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getRotation() {
        return rotation;
    }

    public void setRotation(String rotation) {
        this.rotation = rotation;
    }

    public String getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(String maxSize) {
        this.maxSize = maxSize;
    }

    public int getMaxHistory() {
        return maxHistory;
    }

    public void setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
    }
}
