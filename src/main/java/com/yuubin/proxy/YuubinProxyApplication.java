package com.yuubin.proxy;

import com.yuubin.proxy.config.YuubinProperties;
import com.yuubin.proxy.core.exceptions.ConfigException;
import com.yuubin.proxy.core.exceptions.ProxyException;
import com.yuubin.proxy.core.proxy.ProxyManager;
import com.yuubin.proxy.core.proxy.ProxyServerFactory;
import com.yuubin.proxy.core.services.AuthService;
import com.yuubin.proxy.core.services.LoggingService;
import com.yuubin.proxy.core.services.MetricsService;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Yuubin Proxy application.
 * Handles command-line arguments, configuration loading, and application
 * lifecycle.
 */
@Command(name = "yuubin-proxy", mixinStandardHelpOptions = true, version = "1.0.0", description = "Lightweight multi-protocol proxy server.")
public class YuubinProxyApplication implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(YuubinProxyApplication.class);

    /**
     * Path to the YAML configuration file.
     */
    @Option(names = { "-c", "--config" }, description = "Path to config file (YAML)", defaultValue = "application.yml")
    private String configPath;

    /**
     * Entry point for the Yuubin Proxy application.
     */
    private ProxyManager proxyManager;
    private AuthService authService;
    private LoggingService loggingService;
    private MetricsService metricsService;

    /** Latch to block the main thread until shutdown is triggered. */
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /** Flag to signal background threads to stop. */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Service to watch for configuration file changes.
     * Not volatile because it's only assigned once during startup and read during
     * shutdown.
     */
    private WatchService watchService;

    /** Reference to the registered shutdown hook for cleanup. */
    private Thread shutdownHook;

    /**
     * Main method to launch the application.
     * 
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        new CommandLine(new YuubinProxyApplication()).execute(args);
    }

    /**
     * Bootstraps the application, starts proxy servers, and sets up configuration
     * watching.
     * 
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        try {
            log.info("Starting Yuubin Proxy...");

            YuubinProperties props = loadConfig(configPath);
            this.authService = new AuthService(props);
            this.loggingService = new LoggingService(props);
            this.metricsService = new MetricsService(props);

            ProxyServerFactory factory = new ProxyServerFactory(authService, loggingService,
                    metricsService.getRegistry(), props);
            this.proxyManager = new ProxyManager(props, factory);

            proxyManager.startServers();

            startFileWatcher();
            if (System.getProperty("yuubin.no-command-listener") == null) {
                startCommandListener();
            }

            if (System.getProperty("yuubin.no-shutdown-hook") == null) {
                this.shutdownHook = new Thread(this::stop, "ShutdownHook");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }

            shutdownLatch.await();
            return 0;
        } catch (ConfigException e) {
            log.error("Configuration Error: {}", e.getMessage());
            return 1;
        } catch (ProxyException e) {
            log.error("Fatal proxy error: {}", e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            log.warn("Application interrupted");
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            log.error("Unexpected fatal error", e);
            return 1;
        } finally {
            stop();
        }
    }

    /**
     * Starts an interactive command listener on System.in using a virtual thread.
     */
    private void startCommandListener() {
        Thread.ofPlatform().daemon().name("CommandListener").start(() -> {
            try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
                log.info("Interactive console ready. Type 'reload' to refresh config or 'stop' to exit.");
                while (running.get() && readAndProcessCommand(scanner)) {
                    // Loop continues as long as input is available and stop hasn't been signaled
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Command listener fatal error: {}", e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Reads and processes the next command from the scanner.
     * 
     * @param scanner Input scanner.
     * @return True if a command was processed, false if input was closed.
     */
    private boolean readAndProcessCommand(Scanner scanner) {
        try {
            if (scanner.hasNextLine()) {
                processCommand(scanner.nextLine().trim().toLowerCase());
                return true;
            }
        } catch (NoSuchElementException e) {
            // Signal stop if input is closed
        }
        return false;
    }

    /**
     * Processes a single interactive command from the console.
     * 
     * @param command The command string.
     */
    private void processCommand(String command) {
        if (command.isEmpty()) {
            return;
        }

        switch (command) {
            case "reload" -> reloadConfiguration();
            case "stop", "exit", "quit" -> stop();
            case "help" -> log.info("Available commands: reload, stop, exit, quit, help");
            default -> log.warn("Unknown command: {}. Type 'help' for available commands.", command);
        }
    }

    /**
     * Gracefully stops all running proxy servers, configuration watcher, and
     * background listeners.
     * Also unregisters the shutdown hook to prevent memory leaks in test
     * environments.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down Yuubin Proxy...");

            unregisterShutdownHook();

            if (proxyManager != null) {
                proxyManager.stopAll();
            }
            if (loggingService != null) {
                loggingService.shutdown();
            }
            if (metricsService != null) {
                metricsService.shutdown();
            }
            closeWatchService();
            shutdownLatch.countDown();
        }
    }

    /**
     * Unregisters the JVM shutdown hook safely.
     */
    private void unregisterShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // This is expected if stop() is called from the hook itself
            } catch (Exception e) {
                log.debug("Failed to remove shutdown hook: {}", e.getMessage());
            }
        }
    }

    /**
     * Closes the configuration file watch service.
     */
    private void closeWatchService() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception ignored) {
                // Quietly close the watch service; errors here are expected during rapid
                // shutdown
            }
        }
    }

    /**
     * Reloads the configuration from disk and refreshes the proxy servers.
     */
    private void reloadConfiguration() {
        try {
            log.info("Reloading configuration from {}...", configPath);
            YuubinProperties newProps = loadConfig(configPath);

            this.authService.updateProperties(newProps);
            this.loggingService.updateProperties(newProps);
            this.metricsService.updateProperties(newProps);

            proxyManager.refreshServers(newProps.getProxies());
            log.info("Configuration reloaded successfully.");
        } catch (Exception e) {
            log.error("Failed to reload configuration: {}", e.getMessage());
        }
    }

    /**
     * Starts a background thread to watch for changes in the configuration file.
     * Uses a debounce mechanism to avoid multiple reloads for a single logical
     * change.
     */
    private void startFileWatcher() {
        Thread watcherThread = new Thread(() -> {
            try {
                Path path = Paths.get(configPath).toAbsolutePath();
                Path parent = path.getParent();
                if (parent == null) {
                    return;
                }

                this.watchService = FileSystems.getDefault().newWatchService();
                parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

                log.info("Watching configuration file for changes: {}", path);
                String fileName = path.getFileName().toString();

                runWatcherLoop(fileName);
            } catch (ClosedWatchServiceException e) {
                log.debug("Watch service closed");
            } catch (InterruptedException e) {
                log.debug("File watcher interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("File watcher error: {}", e.getMessage(), e);
                }
            }
        }, "ConfigWatcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    /**
     * Executes the main loop for the configuration file watcher.
     * 
     * @param fileName The name of the file to watch.
     * @throws InterruptedException If the thread is interrupted.
     */
    private void runWatcherLoop(String fileName) throws InterruptedException {
        // Debounce: track the last event time and reload only after 1s of silence.
        final long debounceNanos = 1_000_000_000L;
        long lastEventNano = 0;

        while (running.get()) {
            WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
            if (key != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context().toString().equals(fileName)) {
                        lastEventNano = System.nanoTime();
                    }
                }
                if (!key.reset()) {
                    break;
                }
            }

            if (shouldReload(lastEventNano, debounceNanos)) {
                lastEventNano = 0;
                reloadConfiguration();
            }
        }
    }

    /**
     * Checks if enough time has passed since the last file event to trigger a
     * reload.
     * 
     * @param lastEventNano Timestamp of the last event.
     * @param debounceNanos Debounce threshold.
     * @return True if reload should proceed.
     */
    private boolean shouldReload(long lastEventNano, long debounceNanos) {
        return lastEventNano > 0 && System.nanoTime() - lastEventNano >= debounceNanos;
    }

    /**
     * Loads the configuration from the specified path or classpath.
     * 
     * @param path Path to the configuration file.
     * @return Loaded YuubinProperties.
     * @throws ConfigException if configuration cannot be loaded.
     */
    private YuubinProperties loadConfig(String path) {
        Yaml yaml = new Yaml(new Constructor(YuubinProperties.class, new org.yaml.snakeyaml.LoaderOptions()));

        // 1. Try absolute/relative path
        YuubinProperties fromFile = tryLoadFromFile(yaml, path);
        if (fromFile != null) {
            return fromFile;
        }

        // 2. Try classpath
        YuubinProperties fromClasspath = tryLoadFromClasspath(yaml, path);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        throw new ConfigException("Configuration file not found: " + path);
    }

    /**
     * Attempts to load YAML configuration from a file on disk.
     * 
     * @param yaml SnakeYAML instance.
     * @param path File path.
     * @return YuubinProperties if successful, null otherwise.
     */
    private YuubinProperties tryLoadFromFile(Yaml yaml, String path) {
        File file = new File(path);
        if (file.exists()) {
            try (InputStream is = new FileInputStream(file)) {
                return yaml.load(is);
            } catch (YAMLException e) {
                throw new ConfigException("Invalid YAML in " + path + ": " + e.getMessage());
            } catch (IOException e) {
                throw new ConfigException("Error reading config file: " + path, e);
            }
        }
        return null;
    }

    /**
     * Attempts to load YAML configuration from a classpath resource.
     * 
     * @param yaml SnakeYAML instance.
     * @param path Resource path.
     * @return YuubinProperties if successful, null otherwise.
     */
    private YuubinProperties tryLoadFromClasspath(Yaml yaml, String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return yaml.load(is);
            }
        } catch (YAMLException e) {
            throw new ConfigException("Invalid YAML in classpath resource " + path + ": " + e.getMessage());
        } catch (IOException e) {
            log.debug("Classpath resource lookup failed for {}", path);
        }
        return null;
    }
}
