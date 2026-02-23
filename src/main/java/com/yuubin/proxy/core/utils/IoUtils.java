package com.yuubin.proxy.core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common I/O utility methods for data relay and stream handling.
 */
public class IoUtils {

    private IoUtils() {
        // Utility class
    }

    private static final Logger log = LoggerFactory.getLogger(IoUtils.class);

    /** Buffer size used for bidirectional relay transfers. */
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    /**
     * Relays data between two sockets bidirectionally until one of them is closed.
     * Uses the provided executor to run the two unidirectional relay tasks.
     * 
     * @param s1       The first socket.
     * @param s2       The second socket.
     * @param executor The executor to run relay tasks (ideally a virtual thread
     *                 executor).
     */
    public static void relay(Socket s1, Socket s2, Executor executor) {
        relay(s1, s2, executor, null, null);
    }

    /**
     * Relays data between two streams bidirectionally.
     * Optimized to use the calling thread for one direction, reducing thread count.
     * 
     * @param in1           Input from side 1.
     * @param out1          Output to side 1.
     * @param in2           Input from side 2.
     * @param out2          Output to side 2.
     * @param executor      Executor to run tasks.
     * @param bytesSent     Optional counter for bytes sent (client -&gt; target).
     * @param bytesReceived Optional counter for bytes received (target -&gt;
     *                      client).
     */
    public static void relay(InputStream in1, OutputStream out1, InputStream in2, OutputStream out2,
            Executor executor, Counter bytesSent, Counter bytesReceived) {

        // Forward: 1 -> 2 (Sent) - Run in a separate virtual thread
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            try {
                transferWithCounting(in1, out2, bytesSent);
            } catch (IOException e) {
                log.debug("Relay forward error: {}", e.getMessage());
            } finally {
                closeQuietly(in1);
                closeQuietly(out2);
            }
        }, executor);

        // Backward: 2 -> 1 (Received) - Run in the CURRENT thread
        try {
            transferWithCounting(in2, out1, bytesReceived);
        } catch (IOException e) {
            log.debug("Relay backward error: {}", e.getMessage());
        } finally {
            closeQuietly(in2);
            closeQuietly(out1);
        }

        // Wait for the forward task to complete (it might still be sending data)
        try {
            f1.join();
        } catch (Exception e) {
            log.debug("Bidirectional relay joined with exception: {}", e.getMessage());
        }
    }

    /**
     * Relays data between two sockets bidirectionally with optional traffic
     * instrumentation.
     * Counters should be pre-created by the caller (e.g., at server startup) and
     * passed in
     * to avoid per-connection meter registration overhead.
     * 
     * @param s1            The first socket (client).
     * @param s2            The second socket (target).
     * @param executor      The executor to run relay tasks.
     * @param bytesSent     Optional counter for bytes sent (client -&gt; target).
     * @param bytesReceived Optional counter for bytes received (target -&gt;
     *                      client).
     */
    public static void relay(Socket s1, Socket s2, Executor executor, Counter bytesSent, Counter bytesReceived) {
        try {
            relay(s1.getInputStream(), s1.getOutputStream(), s2.getInputStream(), s2.getOutputStream(),
                    executor, bytesSent, bytesReceived);
        } catch (IOException e) {
            log.debug("Failed to start relay: {}", e.getMessage());
        } finally {
            closeQuietly(s1);
            closeQuietly(s2);
        }
    }

    private static void transferWithCounting(InputStream in, OutputStream out, Counter counter) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            if (counter != null) {
                counter.increment(read);
            }
        }
        out.flush();
    }

    /**
     * Reads a single line of text from an input stream.
     * The line is considered terminated by CRLF (\r\n) or LF (\n).
     * 
     * @param in The input stream to read from.
     * @return The line read, or null if the end of the stream is reached.
     * @throws IOException If an I/O error occurs.
     */
    public static String readLine(InputStream in) throws IOException {
        return readLine(in, 8192); // Default max 8KB
    }

    /**
     * Reads a single line of text from an input stream with a maximum length limit.
     * The line is considered terminated by CRLF (\r\n) or LF (\n).
     * Uses a growable {@code byte[]} buffer and constructs a single {@code String}
     * at the
     * end (ISO-8859-1, matching HTTP/1.1 wire encoding) to avoid per-byte object
     * allocation.
     * 
     * @param in        The input stream to read from.
     * @param maxLength The maximum allowed length of the line.
     * @return The line read, or null if the end of the stream is reached.
     * @throws IOException If an I/O error occurs or the line exceeds maxLength.
     */
    public static String readLine(InputStream in, int maxLength) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int len = 0;
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                if (++len > maxLength) {
                    throw new IOException("Line length exceeds maximum allowed length of " + maxLength);
                }
                buf.write(c);
            }
        }
        if (c == -1 && len == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    /**
     * Safely closes a resource without throwing exceptions.
     * 
     * @param closeable The resource to close.
     */
    public static void closeQuietly(AutoCloseable closeable) {
        closeQuietly(closeable, "resource");
    }

    /**
     * Safely closes a resource, logging any exceptions.
     * 
     * @param closeable The resource to close.
     * @param name      Name of the resource for logging.
     */
    public static void closeQuietly(AutoCloseable closeable, String name) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Error closing {}: {}", name, e.getMessage());
            }
        }
    }
}
