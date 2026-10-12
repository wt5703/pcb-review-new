#!/usr/bin/env sh
set -eu

# 运行参数由部署平台通过 JAVA_OPTS、SPRING_DATASOURCE_* 等环境变量注入。
# 不在镜像内固化数据库、邮件、用户中心或文件服务地址，避免不同环境相互污染。
case "${1:-start}" in
  start)
    exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=75.0} -jar /opt/bms/pcb-review.jar
    ;;
  *)
    exec "$@"
    ;;
esac
