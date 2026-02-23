#!/bin/bash
# Wrapper for Linux/MacOS

BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
JAR_FILE="$BASE_DIR/lib/${project.build.finalName}.jar"
DEFAULT_CONFIG="$BASE_DIR/conf/application.yml"

if [ ! -f "$JAR_FILE" ]; then
    echo "Error: JAR file not found at $JAR_FILE"
    exit 1
fi

HAS_CONFIG=false
for arg in "$@"; do
  if [[ "$arg" == "-c" || "$arg" == "--config" ]]; then
    HAS_CONFIG=true
  fi
done

if [ "$HAS_CONFIG" = false ]; then
    exec java -jar "$JAR_FILE" --config "$DEFAULT_CONFIG" "$@"
else
    exec java -jar "$JAR_FILE" "$@"
fi
