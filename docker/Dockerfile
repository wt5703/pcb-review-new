# 基础镜像与公司其他 BMS 项目保持一致，镜像中已具备 JDK 17 与运行所需的基础组件。
FROM itharbor.leapmotor.com/bms_cloud/bms-project-mysql:V1.0

RUN mkdir -p /opt/bms
WORKDIR /opt/bms

# 先在仓库根目录执行 mvn -Dmaven.test.skip=true package，生成可执行 Spring Boot JAR。
COPY ./target/pcb-review-1.0-SNAPSHOT.jar /opt/bms/pcb-review.jar
COPY ./docker/startup.sh /opt/bms/startup.sh

RUN chmod +x ./startup.sh

EXPOSE 8080
ENTRYPOINT ["./startup.sh", "start"]
