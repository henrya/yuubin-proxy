# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user for security
RUN addgroup -S yuubin && adduser -S yuubin -G yuubin
USER yuubin

COPY --from=build /app/target/yuubin-proxy-1.0.0.jar yuubin-proxy.jar

# Default config location
ENV CONFIG_FILE=/app/config/application.yml

# Expose common proxy ports
EXPOSE 8080 1080 1081

ENTRYPOINT ["java", "-jar", "yuubin-proxy.jar"]
CMD ["--config", "/app/config/application.yml"]
