# Yuubin Proxy

[![CI](https://github.com/henrya/yuubin-proxy/workflows/CI/badge.svg)](https://github.com/henrya/yuubin-proxy/actions/workflows/ci.yml)
[![Build](https://github.com/henrya/yuubin-proxy/workflows/Build/badge.svg)](https://github.com/henrya/yuubin-proxy/actions/workflows/build.yml)
[![Release](https://github.com/henrya/yuubin-proxy/workflows/Release/badge.svg)](https://github.com/henrya/yuubin-proxy/actions/workflows/release.yml)

Yuubin Proxy is a high-performance, lightweight multi-protocol proxy server written in Java. 
It leverages **Virtual Threads (Project Loom)** to handle concurrent connections using a "thread-per-connection" model.

## Motivation

While looking for a lightweight cross-platform proxy solution, drawing the best features from both Apache mod_proxy and Nginx, I was unable to find a solution that was both robust and lightweight. The introduction of **Virtual Threads (Project Loom)** in Java 21 provided the perfect foundation for building a high-performance, lightweight proxy tailored to my needs.

### Why Java?
Beyond performance, portability is a primary objective. Java allows for seamless execution across diverse environments without the need to manage complex build pipelines or platform-specific binaries.

## Features

-   **Multi-Protocol Support**:
    -   **HTTP/HTTPS**: Forward and reverse proxying with rule-based routing and header manipulation.
    -   **HTTPS CONNECT**: L4 TCP tunneling for encrypted traffic.
    -   **SOCKS4/SOCKS4a**: Simple TCP proxying with domain resolution support.
    -   **SOCKS5**: Advanced TCP proxying with username/password authentication.
    -   **WebSockets**: Full support for bidirectional WebSocket tunneling.
-   **Advanced Routing & Load Balancing**:
    -   **Host-Based Routing**: Route requests based on the `Host` header (Virtual Hosts).
    -   **Traffic Shaping**: Per-route **Rate Limiting** (Token Bucket) to protect backends from floods.
    -   **Load Balancing Strategies**:
        -   **Round-Robin**: Distribute traffic evenly (default).
        -   **IP Hash**: Sticky sessions based on client IP for stateful backends.
    -   **Health Checks**: Automatic failover by periodically checking backend health status.
    -   **Header Rewriting**: Rewrites `Location` headers in responses for seamless reverse proxying (ProxyPassReverse equivalent).
-   **Security First**:
    -   **DoS Protection**: Strict I/O bounds on HTTP header parsing, line lengths, and SOCKS domains to prevent memory exhaustion (Out-of-Memory, or OOM) attacks.
    -   **Constant-Time Auth**: Password validation uses `MessageDigest.isEqual` to mitigate timing side-channel attacks.
    -   **Modern TLS**: Enforces `TLSv1.3` and the `PKCS12` keystore format for secure, encrypted proxy endpoints.
    -   **Connection Limits**: Configurable max concurrent connections and aggressive timeouts to thwart Slowloris attacks.
-   **Authentication Providers**:
    -   **YAML File**: Standard user list in a YAML file.
    -   **Kubernetes ConfigMap**: Load credentials from a mounted directory (one file per user).
    -   **Environment Variables**: Inject users via strings like `user1:pass1,user2:pass2`.
-   **Graceful Live Reload**: Automatically detects changes to `application.yml` and updates proxy settings, starting or stopping servers without an application restart.
-   **Zero Framework Overhead**: Built with plain Java SE. No Spring Boot, no heavy dependencies.
-   **High Quality**: >95% code coverage with a comprehensive suite of unit and integration tests (WireMock).

## Architecture

-   **Virtual Thread Per Task**: Maximum concurrency using `Executors.newVirtualThreadPerTaskExecutor()`.
-   **Abstract Lifecycle**: Common server logic is centralized in `AbstractProxyServer`.
-   **Filter Chain**: HTTP proxy uses a modular filter system for Authentication and Logging.
-   **Robust I/O**: Specialized `IOUtils` for bidirectional data relay and stream handling.
-   **Custom Exceptions**: Clear error propagation via a structured `ProxyException` hierarchy.

## Documentation & Examples

Detailed technical documentation and configuration examples are available in the repository:

-   **[Architecture Documentation](docs/ARCHITECTURE.md)**: Deep dive into the system design, featuring ASCII request flow diagrams and UML class relationships.
-   **[Configuration Examples](docs/EXAMPLES.md)**: A collection of real-world setup scenarios including Load Balancing, API Gateway routing, and authenticated SOCKS5 proxies.

## Configuration

The proxy is configured via a YAML file. In the standard distribution, this is located at `conf/application.yml`.

### Example `application.yml`

```yaml
proxies:
  - name: main-http-proxy
    port: 8080
    type: HTTP
    authEnabled: true
    timeout: 60000    # Timeout in ms (default 60s)
    maxRedirects: 3   # Max redirects to follow (default 0)
    tlsEnabled: false # Set to true to enable TLSv1.3
    # keystorePath: my-certs.p12 # Required if TLS is enabled
    # keystorePassword: "secret-password"
    rules:
      - host: "api.example.com"
        path: /v1
        targets:
          - http://backend-1:3000
          - http://backend-2:3000
        loadBalancing: IP_HASH # Sticky sessions (or ROUND_ROBIN)
        rateLimit: 100.0       # 100 requests per second per IP
        burst: 200             # Allow bursts up to 200
        healthCheckPath: /health
        healthCheckInterval: 5000
        reverse: true
      - path: /
        target: http://localhost:8081
  - name: socks5-proxy
    port: 1080
    type: SOCKS5
    authEnabled: true
    timeout: 30000

auth:
  enabled: true
  usersPath: users.yml  # Resolved relative to conf/ or the working directory
  # usersDirectory: /etc/proxy/users  # Kubernetes ConfigMap mount (one file per user)
  # usersEnv: YUUBIN_USERS           # Environment variable (format: "u1:p1,u2:p2")

logging:
  # %m = HTTP Method, %q = Query String, %r = Request path
  format: '%h %l %u %t "%m %r%q" %>s %b'
  logResponse: false
```

### Kubernetes Integration
If needed, for cloud-native deployments, Yuubin Proxy supports loading user credentials from a mounted directory (e.g., a **Kubernetes ConfigMap** or **Secret**):
1. Create a ConfigMap where each key is a username and each value is the password.
2. Mount it to `/app/auth/users`.
3. Set `auth.usersDirectory: /app/auth/users` in `application.yml`.
4. Proxy will automatically load all files in that directory as user credentials.

## Distribution & Deployment

### 1. Linux Native Packages (.deb / .rpm) - Recommended
For Linux users, the fastest and most integrated way to run Yuubin Proxy is via our standalone native packages. These do not require Java to be installed to run!

**Installation:**
Download the `.deb` (Ubuntu/Debian) or `.rpm` (RHEL/CentOS) from the GitHub Releases page:
```bash
sudo dpkg -i yuubin-proxy-*.deb
# OR
sudo rpm -i yuubin-proxy-*.rpm
```

**Service Management:**
Upon installation, a secure `yuubin` system user is created, and the service is automatically registered with `systemd`.

*   **Config Location:** Edit your rules at `/etc/yuubin-proxy/application.yml`
*   **Log Location:** `/var/log/yuubin-proxy/`
*   **Start/Stop:** `sudo systemctl start yuubin-proxy`
*   **Check Status:** `sudo systemctl status yuubin-proxy`

**Reloading Configuration:**
Yuubin Proxy supports **Graceful Live Reload**. If you modify `/etc/yuubin-proxy/application.yml` while the systemd service is actively running, the proxy will detect the file system change and automatically hot-reload the new rules within a few seconds without dropping active connections. 

You only need to restart the service if you are upgrading the binary or need to force a hard JVM reboot:
```bash
sudo systemctl restart yuubin-proxy
```

### 2. Standard Archives (.tar.gz / .zip)
The recommended way to deploy on traditional operating systems (Windows, macOS) or platforms requiring the JVM. These archives require Java 21+ and are attached to every tagged release.

**Build from Source:**
```bash
mvn clean package
```

This generates `target/yuubin-proxy-1.0.0-dist.tar.gz` (and `.zip`), which contains a standard application layout:
```text
yuubin-proxy/
├── bin/
│   ├── run.sh                 # Linux/MacOS launcher
│   └── run.bat                # Windows launcher
├── conf/
│   ├── application.yml        # Default proxy configuration
│   └── users.yml              # Default user credentials
├── lib/
│   └── yuubin-proxy-1.0.0.jar  # The compiled fat JAR
└── README.md
```

**Run:**
```bash
# Extract and enter the directory
tar -xzf yuubin-proxy-*-dist.tar.gz
cd yuubin-proxy-*

# Start the proxy (automatically uses conf/application.yml)
./bin/run.sh
```

### 2. Docker (Recommended for Containerized Environments)
Yuubin Proxy is available as a multi-arch Docker image (amd64/arm64).

```bash
docker run -p 8080:8080 -p 1080:1080 \
  -v $(pwd)/packaging/conf/application.yml:/etc/yuubin-proxy/application.yml \
  ghcr.io/henrya/yuubin-proxy:latest
```

### 3. Docker Compose (Build from Source)
To build and run Yuubin Proxy locally using the provided `docker-compose.yml`, you must compile the Java artifact first:

```bash
# 1. Compile the proxy executable natively
mvn clean package

# 2. Build the Alpine Docker image and start the container
docker-compose -f docker/docker-compose.yml up -d --build
```

### 4. GraalVM Native Image (Optional)
For even faster startup times and a lower memory footprint, Yuubin Proxy can be compiled into a standalone native binary using GraalVM.

**Build:**
```bash
# Build the native binary using the 'native' profile
mvn clean package -Pnative
```

This produces a standalone `yuubin-proxy` binary in the `target/` directory.

**Run:**
```bash
./target/yuubin-proxy --config conf/application.yml
```

## Getting Started

### Prerequisites
-   **Java 21** or higher.
-   **Maven 3.9+** (for building).

### Command Line Options

When running the JAR directly, or via the `run.sh`/`run.bat` scripts, you can override defaults:

| Option | Long Flag | Description |
| :--- | :--- | :--- |
| `-c` | `--config` | Path to the YAML configuration file. |
| `-h` | `--help` | Show this help message and exit. |
| `-V` | `--version` | Print version information and exit. |

Usage:
```bash
# Using the provided script
./bin/run.sh --config custom-config.yml

# Or running the JAR directly
java -jar lib/yuubin-proxy-1.0.0.jar -c custom-config.yml
```

## Interactive Console and Hot Reload

Yuubin Proxy supports a live configuration refresh/reload mechanism while the application is running.
You can type commands directly into the terminal:

- `reload`: Manually triggers a configuration reload from the current config file.
- `stop` / `exit` / `quit`: Gracefully shuts down all proxy servers and exits the application.
- `help`: Lists available interactive commands.

## Health & Metrics

Yuubin Proxy includes endpoints for monitoring and health checks:

- **Endpoint**: `http://localhost:9090` (Configurable via `admin.port`)
- **`/health`**: Returns `OK` (200) if the application is running.
- **`/metrics`**: Exports real-time metrics in **Prometheus** format via Micrometer.

### Available Metrics
- `proxy.connections.active`: Current number of open client connections.
- `proxy.connections.total`: Total number of connections accepted since startup.
- `proxy.connections.errors`: Total count of connection handling errors.
- `proxy.http.requests.total`: Total number of HTTP requests processed.
- `proxy.traffic.bytes.sent`: Total bytes sent to upstream targets.
- `proxy.traffic.bytes.received`: Total bytes received from upstream targets.

## Advanced Features

### Proxy Chaining (Upstream)
Yuubin Proxy can forward its traffic to another proxy server.
```yaml
proxies:
  - name: chained-proxy
    port: 8080
    type: HTTP
    upstreamProxy:
      host: "parent-proxy.com"
      port: 3128
      type: HTTP
      username: "chained-user" # Optional
      password: "secret-password" # Optional
```

### IP Blacklisting
Block specific client IP addresses from connecting to your proxy.
```yaml
globalBlacklist:
  - 192.168.1.100

proxies:
  - name: restricted-proxy
    port: 1080
    type: SOCKS5
    blacklist:
      - 10.0.0.50
```

## Usage Examples

### HTTP Proxy with Authentication
```bash
curl -x http://localhost:8080 -U admin:password123 http://www.google.com
```

### SOCKS5 Proxy with Authentication
```bash
curl --socks5-hostname localhost:1080 --proxy-user user:secret_pass http://www.google.com
```

## Extensibility & Plugins

Yuubin Proxy supports a Service Provider Interface (SPI extensions) for extending its capabilities without modifying the core.

### Custom Proxy Protocols
To implement a custom proxy protocol (e.g., a proprietary binary protocol, gRPC, etc.):
1. Implement the `com.yuubin.proxy.spi.ProxyProvider` interface.
2. Register your implementation in `META-INF/services/com.yuubin.proxy.spi.ProxyProvider`.
3. Package your code as a JAR and add it to the classpath (e.g., inside the `lib/` folder).
4. Configure your proxy in `application.yml` with your custom type:
   ```yaml
   proxies:
     - name: my-custom-proxy
       port: 9999
       type: MY_PROTOCOL
   ```

### Custom Load Balancers
To implement custom load balancing logic (e.g., Weighted Round-Robin, Geo-IP):
1. Implement the `com.yuubin.proxy.spi.LoadBalancer` interface.
2. Package your class in a JAR and add it to the classpath.
3. Configure your rule to use the `CUSTOM` strategy and specify the class name:
   ```yaml
   rules:
     - targets:
         - http://s1
         - http://s2
       loadBalancing: CUSTOM
       customLoadBalancer: com.example.MyWeightedLoadBalancer
   ```
