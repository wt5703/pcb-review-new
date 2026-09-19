# PCB 评审平台

PCB 评审平台包含 Spring Boot 后端与 Vue 3 前端。当前可用本地 H2 Mock 环境完整运行；生产数据库预留 MySQL 8 配置。

## 运行要求

- JDK 17
- Apache Maven 3.9+
- MySQL 8（仅启用 MySQL Profile 时需要）
- Node.js 20+ 与 pnpm（运行前端时需要）

## 本地启动

默认使用内存 H2（MySQL 兼容模式），不需要安装数据库：

```powershell
mvn test
mvn spring-boot:run
```

服务启动后可访问：

- Swagger UI：`http://localhost:8080/api/v1/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/api/v1/openapi.json`

## 前端启动

前端工程位于 `frontend/`，使用 Vue 3、TypeScript 与 Vite，视觉结构对应交互原型中的工作台、任务、互检管理和任务详情多标签页。

先在一个终端启动后端，再在第二个终端启动前端：

```powershell
cd frontend
pnpm install
pnpm dev
```

浏览器访问 `http://localhost:5173`。Vite 已将 `/api/v1` 代理至 `http://localhost:8080`，前端页面右上角可设置本地 Mock 用户与角色。生产构建校验：

```powershell
cd frontend
pnpm build
```

当前后端尚未提供全局邮件记录、用户目录、模板列表查询及任务版本号读取接口；前端相应位置会明确展示接口待提供状态，避免以静态数据冒充真实接口结果。

本地身份 Mock 由下列请求头提供：

| 请求头 | 示例 | 说明 |
| --- | --- | --- |
| `X-Mock-User-Id` | `10` | 当前用户 ID，未传时默认为 `1`。 |
| `X-Mock-Roles` | `DESIGNER` | 当前角色；多角色用英文逗号分隔。 |

常用角色包括 `DESIGNER`、`PCB_LEADER`、`SCHEMATIC_LEADER`、`HARDWARE_EXPERT`、`EMC_EXPERT`、`PROCESS_EXPERT`、`STRUCTURE_EXPERT` 和 `HARDWARE_DEPARTMENT_MANAGER`。

## 启用 MySQL

安装 MySQL 8 后，先创建空数据库：

```sql
CREATE DATABASE pcb_review DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

在 PowerShell 设置连接信息并启动：

```powershell
$env:PCB_REVIEW_DB_URL = 'jdbc:mysql://localhost:3306/pcb_review?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:PCB_REVIEW_DB_USERNAME = 'root'
$env:PCB_REVIEW_DB_PASSWORD = '请替换为本机密码'
mvn spring-boot:run "-Dspring-boot.run.profiles=mysql"
```

Flyway 会自动执行 `src/main/resources/db/migration` 下的版本化建表脚本。切换到真实 MySQL 前应先执行 `mvn test`；本地 H2 数据不会迁移到 MySQL。

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

测试覆盖任务、权限、文件版本、人员分配、检查项、意见闭环、流程结束、归档、Outbox 通知和 OpenAPI 契约。详细业务和工程约束见 [docs](docs)。
