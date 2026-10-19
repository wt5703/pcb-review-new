# PCB 评审平台

PCB 评审平台当前仅保留 Spring Boot 后端，默认使用 MySQL 8；部署时可通过环境变量覆盖数据源连接信息。

## 运行要求

- JDK 17
- Apache Maven 3.9+
- MySQL 8

## 本地启动

默认使用 `application.yml` 中的 MySQL 配置。若本机连接信息不同，请在启动前通过 `SPRING_DATASOURCE_*` 覆盖：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/pcb_review?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:SPRING_DATASOURCE_PASSWORD = '请替换为本机密码'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'com.mysql.cj.jdbc.Driver'
mvn test
mvn spring-boot:run
```

服务启动后可访问：

- Swagger UI：`http://localhost:8080/leapmotor/pcb_review/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/leapmotor/pcb_review/openapi.json`

## 待结束流程

设计者在对应阶段的意见闭环并重新上传终版后，调用流程推进接口并传入动作 `PENDING_FINISH`，任务状态进入 `PENDING_FINISH`（待结束）；随后使用 `FINISH` 结束任务。流程动作和任务状态同名，但分别代表操作和操作结果。

新请求不再使用 `PREPARE_FINISH`。历史流转记录和已产生的事件保留原始动作值，读取历史数据的客户端或消费者需同时识别旧值。

## Docker 部署

后端构建会生成可执行 JAR `target/pcb-review-1.0-SNAPSHOT.jar`。Docker 镜像直接使用该 JAR，并通过 `docker/startup.sh` 启动，避免为单模块 Spring Boot 项目额外引入打包插件。

```powershell
mvn -Dmaven.test.skip=true package
docker build -f docker/Dockerfile -t pcb-review:1.0-SNAPSHOT .
docker run --rm -p 8080:8080 `
  -e SPRING_DATASOURCE_URL='jdbc:mysql://mysql-host:3306/pcb_review?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' `
  -e SPRING_DATASOURCE_USERNAME='pcb_review' `
  -e SPRING_DATASOURCE_PASSWORD='请通过部署平台安全注入' `
  pcb-review:1.0-SNAPSHOT
```

镜像不固化数据库、资源服务、用户中心、邮件等环境配置。部署平台应通过 `SPRING_DATASOURCE_*`、`RESOURCE_SERVICE_API_PREFIX`、`RESOURCE_SERVICE_UPLOAD_PATH`、`RESOURCE_SERVICE_DOWNLOAD_PATH`、`RESOURCE_SERVICE_DIR_PATH`、`RESOURCE_SERVICE_MOCK_ENABLED`、`USER_CENTER_*`、`MAIL_*` 和可选的 `JAVA_OPTS` 环境变量注入对应环境配置；服务默认暴露 `8080` 端口和 `/leapmotor/pcb_review` 上下文路径。

业务接口按实际需要在 Controller 中接收请求头 `userId` 和 `userName`，分别作为员工工号和姓名；无需用户信息的接口不要求这两个请求头，仅需工号的接口不要求姓名。不再调用用户中心当前用户接口或使用默认 Mock 用户，原有业务参数名称不变。暂不校验角色权限，仍按工号校验任务归属和操作人归属。部署时应由可信网关完成身份认证并覆盖身份请求头，禁止外部请求直接伪造身份。

## 启用 MySQL

安装 MySQL 8 后，先创建空数据库：

```sql
CREATE DATABASE pcb_review DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

在 PowerShell 设置连接信息并启动：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/pcb_review?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:SPRING_DATASOURCE_PASSWORD = '请替换为本机密码'
$env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'com.mysql.cj.jdbc.Driver'
mvn spring-boot:run
```

系统不再在启动时自动执行数据库建表或初始化。请使用 MySQL 客户端按顺序手动执行：

```powershell
mysql -h localhost -P 3306 -u root -p pcb_review < docs/database/ddl.sql
mysql -h localhost -P 3306 -u root -p pcb_review < docs/database/init.sql
```

`docs/database/ddl.sql` 是唯一的建表脚本，且每张表的索引定义已内嵌在对应的 `CREATE TABLE` 语句中；`docs/database/init.sql` 仅包含本地开发/演示所需的初始化白名单数据。

## 验证

```powershell
mvn test
```

测试覆盖任务、权限、文件上传与下载、人员分配、检查项、意见闭环、流程结束、归档、Outbox 通知和 OpenAPI 契约。详细业务和工程约束见 [docs](docs)。
