FROM eclipse-temurin:21-jre-alpine

# Metadata
LABEL description="Yuubin High Performance Proxy Server"

# Hardening: Run as a restricted non-root system user
RUN addgroup -S yuubin && adduser -S yuubin -G yuubin

# Set up enterprise directories and set correct ownership
RUN mkdir -p /etc/yuubin-proxy /var/log/yuubin-proxy \
    && chown -R yuubin:yuubin /etc/yuubin-proxy /var/log/yuubin-proxy

USER yuubin

WORKDIR /app

# The build context must be the root of the project to copy the compiled jar
# Note: You MUST build the project locally (mvn clean package) before running docker build!
ARG VERSION=1.0.0
COPY target/yuubin-proxy-${VERSION}.jar yuubin-proxy.jar

# Copy the Enterprise Apache-tier default config
COPY packaging/conf/application.yml /etc/yuubin-proxy/application.yml

# Environment variable pointing to the default configuration
ENV CONFIG_FILE=/etc/yuubin-proxy/application.yml

# Expose HTTP, SOCKS5, SOCKS4, and Prometheus Admin ports
EXPOSE 8080 1080 1081 9090

ENTRYPOINT ["java", "-jar", "yuubin-proxy.jar"]
CMD ["--config", "/etc/yuubin-proxy/application.yml"]
