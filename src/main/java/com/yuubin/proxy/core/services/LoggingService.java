package com.yuubin.proxy.core.services;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yuubin.proxy.config.LoggingConfig;
import com.yuubin.proxy.config.YuubinProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;

/**
 * Service responsible for request and proxy activity logging.
 * Supports configurable Apache-style log formats for HTTP and specialized
 * logging for SOCKS.
 * Uses Logback programmatically to handle file rotation.
 */
public class LoggingService {

    private static final Logger rootLog = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private static final Logger accessLogObj = LoggerFactory.getLogger("ACCESS_LOG");

    private final AtomicReference<YuubinProperties> properties;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");

    /**
     * Cached formatted timestamp, refreshed at most once per second.
     */
    private volatile String cachedTimestamp = "";
    private volatile long cachedTimestampSec = 0;

    /**
     * Initializes the LoggingService with the provided configuration.
     * 
     * @param properties The configuration properties containing logging settings.
     */
    public LoggingService(YuubinProperties properties) {
        this.properties = new AtomicReference<>(properties);
        configureLogback(properties.getLogging());
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
    public void updateProperties(YuubinProperties properties) {
        this.properties.set(properties);
        configureLogback(properties.getLogging());
    }

    private void configureLogback(LoggingConfig config) {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        lc.reset();
        if (!lc.isStarted()) {
            lc.start();
        }

        // Default layout for application logs
        PatternLayoutEncoder rootEncoder = new PatternLayoutEncoder();
        rootEncoder.setContext(lc);
        rootEncoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        rootEncoder.start();

        // Raw layout for access logs
        PatternLayoutEncoder accessEncoder = new PatternLayoutEncoder();
        accessEncoder.setContext(lc);
        accessEncoder.setPattern("%msg%n");
        accessEncoder.start();

        // Root Console Appender
        ConsoleAppender<ILoggingEvent> rootConsole = new ConsoleAppender<>();
        rootConsole.setContext(lc);
        rootConsole.setName("CONSOLE_ROOT");
        rootConsole.setEncoder(rootEncoder);
        rootConsole.start();

        // Async wrapper for Root Console
        AsyncAppender asyncRootConsole = new AsyncAppender();
        asyncRootConsole.setContext(lc);
        asyncRootConsole.setName("ASYNC_CONSOLE_ROOT");
        asyncRootConsole.setQueueSize(1024);
        asyncRootConsole.setDiscardingThreshold(0); // Keep all events
        asyncRootConsole.setMaxFlushTime(5000);
        asyncRootConsole.addAppender(rootConsole);
        asyncRootConsole.start();

        ch.qos.logback.classic.Logger rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        rootLogger.addAppender(asyncRootConsole);

        // Access Logger configuration
        ch.qos.logback.classic.Logger accessLogger = lc.getLogger("ACCESS_LOG");
        accessLogger.setAdditive(false); // Do not bubble up to root
        accessLogger.setLevel(Level.INFO);

        ConsoleAppender<ILoggingEvent> accessConsole = new ConsoleAppender<>();
        accessConsole.setContext(lc);
        accessConsole.setName("CONSOLE_ACCESS");
        accessConsole.setEncoder(accessEncoder);
        // Async wrapper for Access Console
        AsyncAppender asyncAccessConsole = new AsyncAppender();
        asyncAccessConsole.setContext(lc);
        asyncAccessConsole.setName("ASYNC_CONSOLE_ACCESS");
        asyncAccessConsole.setQueueSize(2048); // Slightly larger queue for potentially high-volume access logs
        asyncAccessConsole.setDiscardingThreshold(0);
        asyncAccessConsole.setMaxFlushTime(5000);
        asyncAccessConsole.addAppender(accessConsole);
        asyncAccessConsole.start();

        accessLogger.addAppender(asyncAccessConsole);

        if (config.isFileEnabled()) {
            setupFileAppender(lc, config, accessEncoder, accessLogger);
        }
    }

    private void setupFileAppender(LoggerContext lc, LoggingConfig config, PatternLayoutEncoder encoder,
            ch.qos.logback.classic.Logger accessLogger) {

        RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<>();
        fileAppender.setContext(lc);
        fileAppender.setName("FILE_ACCESS");

        Path logPath = Paths.get(config.getFilePath(), config.getFileName());
        fileAppender.setFile(logPath.toString());
        fileAppender.setEncoder(encoder);

        String rotation = config.getRotation().toUpperCase();
        if ("SIZE".equals(rotation)) {
            SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
            policy.setContext(lc);
            policy.setParent(fileAppender);
            policy.setFileNamePattern(config.getFilePath() + "/" + config.getFileName() + ".%d{yyyy-MM-dd}.%i");
            policy.setMaxFileSize(FileSize.valueOf(config.getMaxSize()));
            policy.setMaxHistory(config.getMaxHistory());
            policy.start();
            fileAppender.setRollingPolicy(policy);
        } else {
            TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
            policy.setContext(lc);
            policy.setParent(fileAppender);

            String datePattern = switch (rotation) {
                case "WEEKLY" -> "yyyy-ww";
                case "MONTHLY" -> "yyyy-MM";
                default -> "yyyy-MM-dd";
            };

            policy.setFileNamePattern(config.getFilePath() + "/" + config.getFileName() + ".%d{" + datePattern + "}");
            policy.setMaxHistory(config.getMaxHistory());
            policy.start();
            fileAppender.setRollingPolicy(policy);
        }

        fileAppender.start();

        // Async wrapper for File Appender
        AsyncAppender asyncFileAppender = new AsyncAppender();
        asyncFileAppender.setContext(lc);
        asyncFileAppender.setName("ASYNC_FILE_ACCESS");
        asyncFileAppender.setQueueSize(4096);
        asyncFileAppender.setDiscardingThreshold(0);
        asyncFileAppender.setMaxFlushTime(5000);
        asyncFileAppender.addAppender(fileAppender);
        asyncFileAppender.start();

        accessLogger.addAppender(asyncFileAppender);

        rootLog.info("File logging enabled. Outputting to: {}", logPath.toAbsolutePath().normalize());
    }

    /**
     * Logs an HTTP request using the configured format.
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
            // Intentionally swallowed: if URI parsing fails, we gracefully fall back to an
            // empty query string
            // so that logging errors do not interrupt or fail the actual proxy request
            // processing.
        }

        LogRecord logRecord = new LogRecord(remoteHost, userStr, time, requestLine, statusStr, byteStr, method, query);
        String logLine = formatLogLine(format, logRecord);

        accessLogObj.info(logLine);

        if (logCfg.isLogResponse()) {
            accessLogObj.info("[RESPONSE] {} {} -> STATUS: {}, BYTES: {}", method, uri, status, byteStr);
        }
    }

    /**
     * Formats a log line based on the Apache-style format string.
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
     */
    public void logSocks(String client, String target, String protocol, int status) {
        String logLine = String.format("%s [%s] %s %s %d", client, getCachedTimestamp(), protocol, target, status);
        accessLogObj.info(logLine);
    }

    /**
     * Gracefully shuts down the logging service.
     */
    public void shutdown() {
        try {
            Thread.sleep(150); // Ensure recent logs enter the AsyncAppender queue before stopping
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        lc.stop();
    }
}
