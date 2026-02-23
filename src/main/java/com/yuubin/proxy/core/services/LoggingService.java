package com.yuubin.proxy.core.services;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.yuubin.proxy.config.LoggingConfig;
import com.yuubin.proxy.config.YuubinProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service responsible for request and proxy activity logging.
 * Supports configurable Apache-style log formats for HTTP and specialized
 * logging for SOCKS.
 * Now also supports file logging with rotation (Daily, Weekly, Monthly, or
 * Size-based).
 */
public class LoggingService {

    private static final Logger log = LoggerFactory.getLogger(LoggingService.class);
    private static final String LOG_QUEUE_FULL_MSG = "Log queue is full, dropping line: {}";

    private final AtomicReference<YuubinProperties> properties;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");

    /**
     * Cached formatted timestamp, refreshed at most once per second to avoid
     * repeated clock + TZ lookups.
     */
    private volatile String cachedTimestamp = "";
    /** The epoch second at which {@link #cachedTimestamp} was last produced. */
    private volatile long cachedTimestampSec = 0;

    // File logging fields
    private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>(10000);
    private final Object lock = new Object();
    private Thread writerThread;
    private volatile boolean running = true;
    private String currentRotationKey;
    private long currentSizeBytes;

    /**
     * Initializes the LoggingService with the provided configuration.
     * 
     * @param properties The configuration properties containing logging settings.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public LoggingService(YuubinProperties properties) {
        this.properties = new AtomicReference<>(properties);
        startWriterThread();
    }

    private void startWriterThread() {
        writerThread = Thread.ofPlatform().daemon().name("logging-writer").start(() -> {
            while (running || !logQueue.isEmpty()) {
                try {
                    String logLine = logQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (logLine != null) {
                        processLogLine(logLine);
                    } else {
                        closeCurrentWriter();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in logging writer thread", e);
                    closeCurrentWriter();
                }
            }
            closeCurrentWriter();
        });
    }

    private BufferedWriter currentWriter;
    private String currentOpenPath;

    private void processLogLine(String logLine) throws IOException {
        LoggingConfig config = properties.get().getLogging();
        if (!config.isFileEnabled()) {
            return;
        }

        synchronized (lock) {
            ensureDirectoryExists(config.getFilePath());
            checkRotation(config);

            Path logPath = Paths.get(config.getFilePath(), config.getFileName());
            String pathStr = logPath.toString();

            if (currentWriter == null || !pathStr.equals(currentOpenPath)) {
                closeCurrentWriter();
                currentWriter = createWriter(logPath);
                currentOpenPath = pathStr;
            }

            writeLogLine(currentWriter, logLine);
            processBatch(currentWriter);
            currentWriter.flush();
        }
    }

    private void processBatch(BufferedWriter bw) throws IOException {
        int batch = 0;
        while (!logQueue.isEmpty() && batch++ < 100) {
            String nextLine = logQueue.poll();
            if (nextLine != null) {
                writeLogLine(bw, nextLine);
            }
        }
    }

    private void closeCurrentWriter() {
        if (currentWriter != null) {
            try {
                currentWriter.close();
            } catch (IOException e) {
                log.error("Failed to close log writer", e);
            }
            currentWriter = null;
            currentOpenPath = null;
        }
    }

    /**
     * Creates a BufferedWriter for the specified log path.
     * 
     * @param logPath The path to the log file.
     * @return A new BufferedWriter instance.
     * @throws IOException If an I/O error occurs.
     */
    private BufferedWriter createWriter(Path logPath) throws IOException {
        return new BufferedWriter(new FileWriter(logPath.toFile(), StandardCharsets.UTF_8, true));
    }

    /**
     * Writes a single log line to the provided writer and updates current size
     * tracking.
     * 
     * @param bw      The writer to use.
     * @param logLine The log line to write.
     * @throws IOException If an I/O error occurs.
     */
    private void writeLogLine(BufferedWriter bw, String logLine) throws IOException {
        bw.write(logLine);
        bw.newLine();
        currentSizeBytes += logLine.length() + System.lineSeparator().length();
    }

    /**
     * Ensures that the parent directory for a log file exists.
     * 
     * @param path The directory path to check/create.
     * @throws IOException If directory creation fails.
     */
    private void ensureDirectoryExists(String path) throws IOException {
        Path dir = Paths.get(path);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * Checks if the log file should be rotated based on the current configuration.
     * 
     * @param config The logging configuration.
     * @throws IOException If an I/O error occurs during rotation.
     */
    private void checkRotation(LoggingConfig config) throws IOException {
        String rotation = config.getRotation().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        String key = "";
        boolean shouldRotate = false;

        switch (rotation) {
            case "DAILY" -> {
                key = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                shouldRotate = isRotationDue(key);
            }
            case "WEEKLY" -> {
                // Using year and week of year
                key = now.format(DateTimeFormatter.ofPattern("yyyy-'W'w"));
                shouldRotate = isRotationDue(key);
            }
            case "MONTHLY" -> {
                key = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                shouldRotate = isRotationDue(key);
            }
            case "SIZE" -> {
                key = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                shouldRotate = isSizeRotationDue(config);
            }
            default -> {
                // No rotation needed for unknown types
            }
        }

        if (shouldRotate) {
            rotate(config, currentRotationKey != null ? currentRotationKey : "old");
            currentRotationKey = key;
            currentSizeBytes = 0;
        } else if (currentRotationKey == null) {
            currentRotationKey = key;
        }
    }

    private boolean isRotationDue(String newKey) {
        return currentRotationKey != null && !currentRotationKey.equals(newKey);
    }

    private boolean isSizeRotationDue(LoggingConfig config) throws IOException {
        long maxSizeBytes = parseSize(config.getMaxSize());
        if (currentSizeBytes == 0) {
            Path logPath = Paths.get(config.getFilePath(), config.getFileName());
            if (Files.exists(logPath)) {
                currentSizeBytes = Files.size(logPath);
            }
        }
        return currentSizeBytes >= maxSizeBytes;
    }

    /**
     * Executes the rotation logic, moving the current log file to a suffixed name.
     * 
     * @param config The logging configuration.
     * @param suffix The suffix to add to the rotated log file (e.g., date).
     * @throws IOException If the file move fails.
     */
    private void rotate(LoggingConfig config, String suffix) throws IOException {
        Path currentLog = Paths.get(config.getFilePath(), config.getFileName());
        if (!Files.exists(currentLog)) {
            return;
        }

        String rotatedFileName = config.getFileName() + "." + suffix;
        Path rotatedLog = Paths.get(config.getFilePath(), rotatedFileName);

        // If size-based, we might have multiple rotations in same second, though
        // unlikely with current formatter
        // but just in case:
        int i = 1;
        while (Files.exists(rotatedLog)) {
            rotatedLog = Paths.get(config.getFilePath(), rotatedFileName + "." + i++);
        }

        Files.move(currentLog, rotatedLog);
        cleanupOldLogs(config);
    }

    /**
     * Deletes older log files that exceed the maximum history limit.
     * 
     * @param config The logging configuration.
     */
    private void cleanupOldLogs(LoggingConfig config) {
        try {
            File dir = new File(config.getFilePath());
            File[] files = dir.listFiles((d, name) -> name.startsWith(config.getFileName() + "."));
            if (files != null && files.length > config.getMaxHistory()) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                int toDelete = files.length - config.getMaxHistory();
                for (int i = 0; i < toDelete; i++) {
                    if (!Files.deleteIfExists(files[i].toPath())) {
                        log.warn("Failed to delete old log file: {}", files[i].getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error during log cleanup", e);
        }
    }

    /**
     * Parses a size string (e.g., "10MB", "1GB") into bytes.
     * 
     * @param size The size string.
     * @return The size in bytes.
     */
    private long parseSize(String size) {
        String s = size.toUpperCase().trim();
        long multiplier = 1;
        if (s.endsWith("KB")) {
            multiplier = 1024L;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("MB")) {
            multiplier = 1024L * 1024;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("GB")) {
            multiplier = 1024L * 1024 * 1024;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("B")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Long.parseLong(s.trim()) * multiplier;
        } catch (NumberFormatException e) {
            return 10L * 1024 * 1024; // Default 10MB
        }
    }

    /**
     * Internal record to group logging context for formatting.
     */
    record LogRecord(String remoteHost, String user, String time, String requestLine,
            String status, String bytes, String method, String query) {
    }

    /**
     * Updates the logging configuration.
     * 
     * @param properties The new configuration properties.
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public void updateProperties(YuubinProperties properties) {
        synchronized (lock) {
            this.properties.set(properties);
            // Reset rotation key to trigger re-check if config changed
            this.currentRotationKey = null;
            this.currentSizeBytes = 0;
        }
    }

    /**
     * Logs an HTTP request using the configured format.
     * 
     * @param remoteHost Client's IP or hostname.
     * @param user       Authenticated username (if any).
     * @param method     HTTP method (GET, POST, etc.).
     * @param uri        Request URI.
     * @param status     HTTP response status code.
     * @param bytes      Number of bytes sent in the response body.
     */
    public void logRequest(String remoteHost, String user, String method, String uri, int status, long bytes) {
        YuubinProperties currentProps = this.properties.get();
        LoggingConfig logCfg = currentProps.getLogging();
        String format = logCfg.getFormat();
        String time = "[" + getCachedTimestamp() + "]";
        String byteStr = bytes > 0 ? String.valueOf(bytes) : "-";
        String userStr = user != null ? user : "-";
        String statusStr = String.valueOf(status);

        String requestLine = method + " " + uri + " HTTP/1.1";
        String query = "";
        try {
            int queryIndex = uri.indexOf('?');
            if (queryIndex != -1) {
                query = uri.substring(queryIndex);
            }
        } catch (Exception ignored) {
            // Ignored: best effort to extract query
        }

        LogRecord logRecord = new LogRecord(remoteHost, userStr, time, requestLine, statusStr, byteStr, method, query);
        String logLine = formatLogLine(format, logRecord);

        log.info(logLine);
        if (logCfg.isFileEnabled() && !logQueue.offer(logLine)) {
            log.debug(LOG_QUEUE_FULL_MSG, logLine);
        }

        if (logCfg.isLogResponse()) {
            String respLine = String.format("[RESPONSE] %s %s -> STATUS: %d, BYTES: %s", method, uri, status, byteStr);
            log.info(respLine);
            if (logCfg.isFileEnabled() && !logQueue.offer(respLine)) {
                log.debug(LOG_QUEUE_FULL_MSG, respLine);
            }
        }
    }

    /**
     * Formats a log line based on the Apache-style format string.
     * Supported tokens: %h, %l, %u, %t, %r, %>s, %b, %m, %q.
     * 
     * @param format    The format string.
     * @param logRecord The logging context containing all request data.
     * @return The formatted log line.
     */
    private String formatLogLine(String format, LogRecord logRecord) {
        StringBuilder sb = new StringBuilder(format.length() + 100);
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '%' && i + 1 < format.length()) {
                i = appendToken(sb, format, i, logRecord);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Appends a specific token value to the formatted log line.
     *
     * @param sb         The StringBuilder to append to.
     * @param format     The format string being parsed.
     * @param currentIdx The current position in the format string (at '%').
     * @param logRecord  The logging context.
     * @return The new index position in the format string after the token.
     */
    private int appendToken(StringBuilder sb, String format, int currentIdx, LogRecord logRecord) {
        char next = format.charAt(currentIdx + 1);
        int skip = 1;
        switch (next) {
            case 'h' -> sb.append(logRecord.remoteHost());
            case 'l' -> sb.append('-');
            case 'u' -> sb.append(logRecord.user());
            case 't' -> sb.append(logRecord.time());
            case 'r' -> sb.append(logRecord.requestLine());
            case 'm' -> sb.append(logRecord.method());
            case 'q' -> sb.append(logRecord.query());
            case '>' -> {
                if (currentIdx + 2 < format.length() && format.charAt(currentIdx + 2) == 's') {
                    sb.append(logRecord.status());
                    skip = 2;
                } else {
                    sb.append('%');
                    skip = 0;
                }
            }
            case 'b' -> sb.append(logRecord.bytes());
            default -> {
                sb.append('%');
                skip = 0;
            }
        }
        return currentIdx + skip + 1;
    }

    /**
     * Returns a formatted timestamp string for the current second.
     * Refreshes the cached value at most once per second so that repeated calls
     * within the same second avoid redundant clock lookups and timezone resolution.
     * 
     * @return Formatted timestamp string (e.g.,
     *         {@code 22/Feb/2026:23:30:00 +0900}).
     */
    private String getCachedTimestamp() {
        long nowSec = java.time.Instant.now().getEpochSecond();
        if (nowSec != cachedTimestampSec) {
            cachedTimestampSec = nowSec;
            cachedTimestamp = ZonedDateTime.now().format(DATE_FORMATTER);
        }
        return cachedTimestamp;
    }

    /**
     * Logs SOCKS proxy activity.
     *
     * @param client   Client's IP.
     * @param target   Target host and port.
     * @param protocol The protocol version (SOCKS4 or SOCKS5).
     * @param status   SOCKS response code or status indicator.
     */

    public void logSocks(String client, String target, String protocol, int status) {

        YuubinProperties currentProps = this.properties.get();
        String logLine = String.format("%s [%s] %s %s %d", client, getCachedTimestamp(), protocol,
                target, status);
        log.info(logLine);
        if (currentProps.getLogging().isFileEnabled() && !logQueue.offer(logLine)) {
            log.debug(LOG_QUEUE_FULL_MSG, logLine);
        }
    }

    /**
     * Gracefully shuts down the logging service.
     */
    public void shutdown() {
        running = false;
        if (writerThread != null) {
            try {
                // Allow up to 5 s for the writer thread to drain its queue before giving up.
                // The previous 100 ms timeout could silently discard the final log lines on JVM
                // exit.
                writerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
