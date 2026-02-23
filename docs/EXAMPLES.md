# Yuubin Proxy Configuration Examples

This folder contains documentation for various configuration scenarios. The YAML files are located in the `examples/` subfolder.

## 1. Load Balanced Reverse Proxy
**File:** [examples/load-balanced-reverse-proxy.yml](examples/load-balanced-reverse-proxy.yml)  
**Description:** Distributes incoming traffic across three backend servers. It uses `IP_HASH` to ensure session persistence (sticky sessions) and includes automatic health checks to skip offline servers.

## 2. API Gateway & Virtual Hosts
**File:** [examples/api-gateway-routing.yml](examples/api-gateway-routing.yml)  
**Description:** Demonstrates host-based and path-based routing. Routes `api.example.com` differently based on the version path (`/v1` vs `/v2`) and handles `web.example.com` separately.

## 3. Secure SOCKS5 Proxy
**File:** [examples/secure-socks5-proxy.yml](examples/secure-socks5-proxy.yml)  
**Description:** A classic SOCKS5 proxy configuration with mandatory username/password authentication enabled.

## 4. Rate Limiting & TLS (HTTPS)
**File:** [examples/rate-limited-tls-proxy.yml](examples/rate-limited-tls-proxy.yml)  
**Description:** Configures an HTTPS endpoint (TLS v1.3). It includes a per-IP rate limit on the `/api/search` path to prevent DDoS or scraping abuse.

## 5. Proxy Chaining (Upstream)
**File:** [examples/proxy-chaining-upstream.yml](examples/proxy-chaining-upstream.yml)  
**Description:** Useful in corporate environments where you must route external requests through a central parent proxy.

## 6. Advanced Logging
**File:** [examples/logging-advanced.yml](examples/logging-advanced.yml)
**Description:** Showcases custom log formats including request methods, query parameters, and optional response auditing.

---

### How to use these examples
To start the proxy with a specific example:
```bash
./bin/run.sh --config docs/examples/api-gateway-routing.yml
```
