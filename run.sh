#!/bin/bash
# Wrapper for Linux/MacOS. Supports all CLI options (e.g., ./run.sh --help)
JAR_FILE="target/yuubin-proxy-1.0.0.jar"

# Skip Maven build for help/version flags
IS_HELP=false
for arg in "$@"; do
  if [[ "$arg" == "-h" || "$arg" == "--help" || "$arg" == "-V" || "$arg" == "--version" ]]; then
    IS_HELP=true
  fi
done

if [ ! -f "$JAR_FILE" ] && [ "$IS_HELP" = false ]; then
    echo "JAR file not found. Building with Maven..."
    mvn clean package -DskipTests
fi

if [ -f "$JAR_FILE" ] || [ "$IS_HELP" = true ]; then
    java -jar "$JAR_FILE" "$@"
else
    echo "Error: JAR file not found and cannot build (not a help/version command)."
    exit 1
fi
