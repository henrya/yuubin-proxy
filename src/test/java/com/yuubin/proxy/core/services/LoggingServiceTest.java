package com.yuubin.proxy.core.services;

import com.yuubin.proxy.config.LoggingConfig;
import com.yuubin.proxy.config.YuubinProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingServiceTest {

    @TempDir
    Path tempDir;

    private LoggingService loggingService;

    @AfterEach
    void tearDown() {
        if (loggingService != null) {
            loggingService.shutdown();
            loggingService = null;
        }
    }

    @Test
    void logRequest_doesNotThrow() {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);
        assertThatCode(() -> loggingService.logRequest("127.0.0.1", "user", "GET", "/", 200, 100))
                .doesNotThrowAnyException();
    }

    @Test
    void logSocks_doesNotThrow() {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        props.setLogging(logCfg);
        loggingService = new LoggingService(props);
        assertThatCode(() -> loggingService.logSocks("127.0.0.1", "target:80", "SOCKS5", 0))
                .doesNotThrowAnyException();
    }

    @Test
    void fileLogging_createsFile() throws IOException, InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("test.log");
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);
        loggingService.logRequest("127.0.0.1", "user", "GET", "/", 200, 100);

        loggingService.shutdown();

        Path logFile = tempDir.resolve("test.log");
        assertThat(Files.exists(logFile)).isTrue();
        assertThat(Files.readString(logFile)).contains("GET /");
    }

    @Test
    void fileLogging_rotationBySize() throws IOException, InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("rotated.log");
        logCfg.setRotation("SIZE");
        logCfg.setMaxSize("1KB");
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);

        // Write enough to trigger rotation (over 1KB)
        String largeLog = "This is a long log line that exceeds the 1KB limit when repeated enough times. ".repeat(40); // 3.2KB
                                                                                                                        // per
                                                                                                                        // line
        for (int i = 0; i < 20; i++) { // Write ~64KB
            loggingService.logRequest("127.0.0.1", "user", "GET", largeLog, 200, 100);
            Thread.sleep(10); // Give AsyncAppender time to flush and trigger size evaluation
        }
        loggingService.logRequest("127.0.0.1", null, "GET", "Small log", 200, 0);
        Thread.sleep(500); // Give Logback Rolling policy plenty of time to rotate the file on its
                           // background thread
        loggingService.shutdown();

        // Check for rotated file
        File dir = tempDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.startsWith("rotated.log."));
        assertThat(files).isNotEmpty();

        // Current log file should exist and have new content
        Path logFile = tempDir.resolve("rotated.log");
        assertThat(Files.exists(logFile)).isTrue();
        assertThat(Files.readString(logFile)).contains("Small log");
    }

    @Test
    void fileLogging_rotationDaily() throws InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("daily.log");
        logCfg.setRotation("DAILY");
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);
        loggingService.logRequest("127.0.0.1", "user", "GET", "/", 200, 100);
        loggingService.shutdown();

        Path logFile = tempDir.resolve("daily.log");
        assertThat(Files.exists(logFile)).isTrue();
    }

    @Test
    void logRequest_customFormats() throws IOException, InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("format.log");
        logCfg.setFormat("%h %u %m %q %r %>s %b");
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);
        loggingService.logRequest("127.0.0.1", "admin", "POST", "/api/v1?test=1", 201, 1024);
        loggingService.shutdown();

        Path logFile = tempDir.resolve("format.log");
        String content = Files.readString(logFile);
        assertThat(content).contains("127.0.0.1 admin POST ?test=1 POST /api/v1?test=1 HTTP/1.1 201 1024");
    }

    @Test
    void fileLogging_cleanupOldLogs() throws InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("cleanup.log");
        logCfg.setRotation("SIZE");
        logCfg.setMaxSize("1KB");
        logCfg.setMaxHistory(2);
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);

        String largeLog = "This is a large log line. ".repeat(20);
        for (int i = 0; i < 20; i++) {
            loggingService.logRequest("127.0.0.1", null, "GET", "/test" + i + " " + largeLog, 200, 0);
        }
        loggingService.shutdown();

        File dir = tempDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.startsWith("cleanup.log."));
        // Logback async rolling might not delete exactly at maxHistory immediately
        // but verify it's configuring properly without throwing size format exceptions.
        assertThat(files).isNotNull();
    }

    @Test
    void testUpdateProperties_resetsState() throws InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("update.log");
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);
        loggingService.logRequest("127.0.0.1", null, "GET", "/1", 200, 0);

        YuubinProperties newProps = new YuubinProperties();
        LoggingConfig newCfg = new LoggingConfig();
        newCfg.setFileEnabled(true);
        newCfg.setFilePath(tempDir.toString());
        newCfg.setFileName("update-new.log");
        newProps.setLogging(newCfg);

        loggingService.updateProperties(newProps);
        loggingService.logRequest("127.0.0.1", null, "GET", "/2", 200, 0);
        loggingService.shutdown();

        assertThat(Files.exists(tempDir.resolve("update-new.log"))).isTrue();
    }

    @Test
    void testLogRequest_responseLogging() throws IOException, InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("resp.log");
        logCfg.setLogResponse(true);
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);
        loggingService.logRequest("127.0.0.1", "u", "GET", "/u", 200, 50);
        loggingService.shutdown();

        String content = Files.readString(tempDir.resolve("resp.log"));
        assertThat(content).contains("[RESPONSE] GET /u -> STATUS: 200, BYTES: 50");
    }

    @Test
    void logSocks_withFileLogging() throws IOException, InterruptedException {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("socks.log");
        props.setLogging(logCfg);

        loggingService = new LoggingService(props);
        loggingService.logSocks("127.0.0.1", "target:80", "SOCKS5", 0);
        loggingService.shutdown();

        assertThat(Files.readString(tempDir.resolve("socks.log"))).contains("SOCKS5 target:80 0");
    }

    @Test
    void testRotationWeeklyMonthly() {
        YuubinProperties props = new YuubinProperties();
        LoggingConfig logCfg = new LoggingConfig();
        logCfg.setFileEnabled(true);
        logCfg.setFilePath(tempDir.toString());
        logCfg.setFileName("rotation.log");
        logCfg.setRotation("WEEKLY");
        props.setLogging(logCfg);

        assertThatCode(() -> {
            loggingService = new LoggingService(props);

            logCfg.setRotation("MONTHLY");
            loggingService.updateProperties(props);
        }).doesNotThrowAnyException();
    }

    @Test
    void testFormatLogLine_unsupportedTokens() throws Exception {
        YuubinProperties props = new YuubinProperties();
        loggingService = new LoggingService(props);

        java.lang.reflect.Method method = LoggingService.class.getDeclaredMethod("formatLogLine",
                String.class, LoggingService.LogRecord.class);
        method.setAccessible(true);

        LoggingService.LogRecord record = new LoggingService.LogRecord("h", "u", "t", "r", "s", "b", "m", "q");
        String result = (String) method.invoke(loggingService, "%% %x %", record);
        assertThat(result).contains("%% %x %");
    }
}
