#!/usr/bin/env bash
set -e

APPLICATION_NAME="pcb-review"
APP_HOME="/opt/bms"
JAR_NAME="pcb-review-1.0-SNAPSHOT.jar"

cd "${APP_HOME}"

echo "========== PCB Review Startup =========="
echo "Application: ${APPLICATION_NAME}"
echo "JAR: ${JAR_NAME}"

if [ ! -f "${JAR_NAME}" ]; then
    echo "ERROR: JAR file not found: ${APP_HOME}/${JAR_NAME}"
    ls -lah "${APP_HOME}"
    exit 1
fi

echo "Starting PCB Review..."

exec java -jar "${APP_HOME}/${JAR_NAME}" \
    --spring.config.additional-location=file:/opt/bms/application.yml