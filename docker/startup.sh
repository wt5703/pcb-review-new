#!/usr/bin/env bash
set -e

APPLICATION_NAME="pcb-review"
APP_HOME="/opt/bms"
JAR_NAME="pcb-review-1.0-SNAPSHOT.jar"

# 通过环境变量 SPRING_PROFILES_ACTIVE 区分部署环境（同一分支、同一镜像）：
#   - 测试环境：SPRING_PROFILES_ACTIVE=test，加载 application-test.yml
#   - 正式环境：不设置（留空），加载 application.yml
PROFILE="${SPRING_PROFILES_ACTIVE:-}"

cd "${APP_HOME}"

echo "========== PCB Review Startup =========="
echo "Application: ${APPLICATION_NAME}"
echo "JAR: ${JAR_NAME}"
echo "Profile: ${PROFILE:-default(production)}"

if [ ! -f "${JAR_NAME}" ]; then
    echo "ERROR: JAR file not found: ${APP_HOME}/${JAR_NAME}"
    ls -lah "${APP_HOME}"
    exit 1
fi

echo "Starting PCB Review..."

# spring.config.additional-location 指向目录，Spring 会自动按 profile 加载
# application.yml 与 application-${PROFILE}.yml，后者优先级更高。
if [ -n "${PROFILE}" ]; then
    exec java -jar "${APP_HOME}/${JAR_NAME}" \
        --spring.config.additional-location=file:/opt/bms/ \
        --spring.profiles.active="${PROFILE}"
else
    exec java -jar "${APP_HOME}/${JAR_NAME}" \
        --spring.config.additional-location=file:/opt/bms/
fi