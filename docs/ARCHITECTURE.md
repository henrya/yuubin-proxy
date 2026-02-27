# Yuubin Proxy - Architecture Documentation

This document describes the high-level architecture and design principles of the Yuubin Proxy.

## System Overview

Yuubin Proxy is a lightweight, high-performance multi-protocol proxy server implemented in Java 21. It is designed around the "Thread-per-Connection" model, optimized by the use of **Java Virtual Threads (Project Loom)**.

## Request Flow

```text
                                 +-----------------------+
                                 | Config Watcher (Live) |
                                 +----------+------------+
                                            |
                                            v
                                 +-----------------------+      +-----------------+
                                 |  Proxy Manager (Orch) |      |  Admin Server   |
                                 +----------+------------+      | (Metrics/Health)|
                                            |                   +-----------------+
                                            v
+----------+      +-----------+      +-------------------+      +-----------------+
|  Request |      | TCP Entry |      |   Filter Chain    |      |  Router (Rules) |
|  Source  +----->+ (Virtual  +----->+ (Auth & Logging)  +----->+ & Rate Limiting |
| (Client) |      | Threads)  |      |                   |      |                 |
+----------+      +-----------+      +-------------------+      +--------+--------+
                                                                         |
                                                                         v
                                                                +-----------------+
                                                                |  Load Balancer  |
                                                                +--------+--------+
                                                                         |
                                                                         v
                                                                +-----------------+
                                                                |  Final Target   |
                                                                | (Upstream Srv)  |
                                                                +-----------------+
```

## Class Relationships (UML ASCII)

```text
+---------------------------+        +----------------------+
| YuubinProxyApplication    |        |    ProxyManager      |
+---------------------------+        +----------------------+
| - proxyManager            +------->| - activeServers      |
| - authService             |        | - factory            |
| - loggingService          |        +-----------+----------+
| - metricsService          |                    |
+-------------+-------------+                    | manages
              |                                  v
              | owns                 +-----------+----------+
              v                      | <<interface>>        |
+-------------+-------------+        |    ProxyServer       |
| AuthService               |        +-----------+----------+
+---------------------------+                    ^
| - users: Map              |                    | implements
+-------------+-------------+        +-----------+----------+
              ^                      | AbstractProxyServer  |
              | used by              +----------------------+
              |                      | # authService        |
+-------------+-------------+        | # loggingService     |
| LoggingService            |        | # executor           |
+---------------------------+        | # connectionSemaphore|
| - cachedTimestamp: Atomic |        +-----+-------+------+-+
+---------------------------+              ^       ^      ^
                                           |       |      | extends
                                           |       |      +-----------------------+
              +----------------------------+       +-------------+                |
              |                                                  |                |
+-------------+-------------+        +-----------------------+   |   +------------+----------+
|    HttpProxyServer        |        |  Socks5ProxyServer    |   |   |  Socks4ProxyServer    |
+---------------------------+        +-----------------------+   |   +-----------------------+
| - httpClient              |        | - requestsTotal       |   |   | - requestsTotal       |
| - filters: List           |        +-----------------------+   |   +-----------------------+
| - healthCheckExecutor     |                                    |
+-------------+-------------+                                    |
              |                                                  |
              | uses                                             |
              v                                                  |
+-------------+-------------+        +-----------------------+   |
|          Rule             |        |    UpstreamConnector  |<--+
+---------------------------+        +-----------------------+
| - loadBalancer: Strategy  |        | + connect()           |
| - buckets: Map (RateLimit)|        +-----------------------+
+-------------+-------------+
              |
              | uses
              v
+-------------+-------------+
| <<interface>>             |
|    LoadBalancer           |
+-------------+-------------+
| + select()                |
+------+--------------+-----+
       ^              ^
       |              |
+------+-------+  +---+----------+
| IpHashLB     |  | RoundRobinLB |
+--------------+  +--------------+
```

## Core Components

### 1. Application Entry & Lifecycle
- **Picocli Integration**: Handles command-line arguments.
- **Config Watcher**: Uses a `WatchService` to monitor `application.yml` for changes, triggering atomic reloads without downtime.
- **Interactive Console**: Provides a daemon thread for manual commands (`reload`, `stop`).

### 2. Proxy Management (`ProxyManager`)
- Orchestrates multiple `ProxyServer` instances.
- Handles dynamic refreshes: identifies changed configurations to restart only affected servers while leaving others untouched.

### 3. Connection Handling (`AbstractProxyServer`)
- **Virtual Thread Executor**: Every accepted socket is handled in its own virtual thread, allowing the system to scale to thousands of concurrent connections with minimal memory overhead.
- **Semaphore-based Throttling**: Limits maximum concurrent connections per proxy instance to protect system resources.
- **IP Blacklisting**: Performs O(1) lookups against a `HashSet` of blocked IPs before accepting traffic.

### 4. Protocol Implementations
- **HTTP/HTTPS (`HttpProxyServer`)**:
    - Uses a **Filter Chain** (Authentication, Logging).
    - Implements a custom **Rule-based Router** supporting host and path matching.
    - **HTTPS CONNECT** tunneling respects host-based routing rules, automatically applying per-host upstream proxies.
    - Supports **WebSocket** tunneling.
    - Rejects duplicate security-sensitive headers (e.g., `Proxy-Authorization`) to prevent injection attacks.
- **SOCKS (`Socks4ProxyServer`, `Socks5ProxyServer`)**:
    - RFC-compliant implementations for SOCKS4, SOCKS4a, and SOCKS5.
    - Supports Username/Password authentication for SOCKS5.

### 5. Routing & Load Balancing (`Rule`)
- **Token Bucket Rate Limiting**: Per-IP rate limiting to prevent backend abuse.
- **Load Balancing**:
    - **Round-Robin**: Default distribution.
    - **IP Hash**: Sticky sessions based on client IP.
- **Health Checks**: Background threads periodically verify target health, automatically removing unhealthy nodes from rotation.

### 6. Support Services
- **AuthService**: Manages user credentials from YAML, Environment Variables, or mounted directories. Uses constant-time equality checks for password validation.
- **LoggingService**: Extends the **Logback Engine** securely using **`AsyncAppender`**s around `RollingFileAppender` and `ConsoleAppender`. It dynamically instantiates rotation policies from YAML rather than requiring a static `logback.xml` file, keeping the deployment minimal. Timestamp caching uses an `AtomicReference`-based record for thread-safe, lock-free access.
- **MetricsService**: Integrated Micrometer support with a Prometheus endpoint and health check listener. Admin server binds to `127.0.0.1` by default for security; configurable via `admin.bindAddress`.

## Data Flow (HTTP Example)

1. **Client** connects to the listening port.
2. **Virtual Thread** is spawned to handle the connection.
3. **Filter Chain** executes (e.g., Auth check).
4. **Router** matches the request to a `Rule`.
5. **Rate Limiter** checks if the request is within limits.
6. **Load Balancer** selects a healthy **Target**.
7. **IOUtils.relay** establishes a bidirectional bridge between the Client and Target.
8. **LoggingService** records the activity.
