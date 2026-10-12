# PCB 评审平台

PCB 评审平台包含 Spring Boot 后端与 Vue 3 前端，默认使用 MySQL 8；部署时可通过环境变量覆盖数据源连接信息。

## 运行要求

- JDK 17
- Apache Maven 3.9+
- MySQL 8
- Node.js 20+ 与 pnpm（运行前端时需要）

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

## 前端启动

前端工程位于 `frontend/`，使用 Vue 3、TypeScript 与 Vite，视觉结构对应交互原型中的工作台、任务、互检管理和任务详情多标签页。

先在一个终端启动后端，再在第二个终端启动前端：

```powershell
cd frontend
pnpm install
pnpm dev
```

浏览器访问 `http://localhost:5173`。Vite 已将 `/leapmotor/pcb_review` 代理至 `http://localhost:8080`，前端页面右上角可设置本地 Mock 用户与角色。生产构建校验：

```powershell
cd frontend
pnpm build
```

当前后端尚未提供全局邮件记录、用户目录、模板列表查询及任务版本号读取接口；前端相应位置会明确展示接口待提供状态，避免以静态数据冒充真实接口结果。

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

开发环境未配置 `USER_CENTER_CURRENT_USER_URL` 时，后端使用王鹏飞全权限 Mock 身份，方便本地联调；不再接受前端通过请求头、工号或用户 ID 伪造当前用户。生产环境必须配置当前用户接口地址。该接口不带员工工号，后端仅转发浏览器/网关注入的 `Authorization` 登录态，由用户中心识别当前登录人员。

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

Flyway 会自动执行 `src/main/resources/db/migration` 下的版本化建表脚本。变更 MySQL 连接信息后应先执行 `mvn test`。

MySQL 安装并配置完成后，可显式执行真实数据库迁移冒烟测试：

```powershell
$env:PCB_REVIEW_RUN_MYSQL_TESTS = 'true'
mvn test "-Dtest=MySqlMigrationIntegrationTest"
```

该测试仅在 `PCB_REVIEW_RUN_MYSQL_TESTS=true` 时连接 MySQL，并验证数据库类型和 Flyway 当前版本；未设置开关时会自动跳过，避免常规构建误连接数据库。

## 验证

```powershell
mvn test
```

测试覆盖任务、权限、文件上传与下载、人员分配、检查项、意见闭环、流程结束、归档、Outbox 通知和 OpenAPI 契约。详细业务和工程约束见 [docs](docs)。
