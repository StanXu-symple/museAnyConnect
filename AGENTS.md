# AGENTS.md — MuseAnyConnect 全局开发规范

本文档定义 `museAnyConnect` 项目的全局技术约束、编码规范与架构原则，所有贡献者及 AI 助手在修改或扩展本项目时必须遵守。

## 1. 技术栈约束
- **Java 版本**：JDK 17（可使用 `record`、`switch` 表达式、文本块、原生 `java.net.http.HttpClient`）。
- **Spring Boot**：3.5.x（Maven 构建）。
- **数据库**：SQLite 轻量级嵌入式文件数据库（`org.xerial:sqlite-jdbc`，WAL 模式，默认路径 `${MUSE_DATA_DIR}/sqlite/muse-connect.db`），仅使用 `spring-boot-starter-jdbc`（`JdbcTemplate`），**禁止引入 JPA / Hibernate / MyBatis**，以保持极低的内存占用与启动开销。
- **数据卷与日志**：统一挂载 `/data`（本地默认 `./data`），内部包含 `sqlite/`（数据库文件）与 `logs/`（滚动日志文件）。
- **前端**：Thymeleaf + 原生 HTML/CSS/JS，**禁止引入前端打包工具链（Node/Webpack/Vite）**。
- **SSH**：`com.github.mwiede:jsch`，每次请求独立建立并及时关闭 Session/Channel，禁止泄漏连接。

## 2. 数据库极简原则
1. SQLite 仅存储核心连接配置表 `connector_profile`，不得在 SQLite 中增加日志表、历史记录表、用户会话表或大字段附件表。
2. HikariCP 连接池保持单写入连接（`maximum-pool-size=1`）并启用 WAL + `busy_timeout=5000`，避免锁冲突并维持极低内存占用。
3. 临时观测数据（如最近中转请求流水）一律存放在 JVM 定长环形缓冲区（`RelayActivityBuffer`），严格限制上限条数。

## 3. 代码组织与安全规范
- 包根目录：`com.muse.connect`
- 所有对外返回的连接配置 DTO 必须对敏感字段（`bearerToken`, `apiSecret`, `accessTokenSecret`, `authToken`, `ct0`, `botToken`, `password`, `privateKey`, `passphrase`）进行脱敏；编辑更新时，若前端提交占位符或空值，应保留原库中已存密钥（除非用户明确清除/替换）。
- 所有外部调用（X / Telegram / SSH）必须具备明确的超时控制，禁止无限阻塞线程。
- 新增接口或功能后，同步更新 `README.md` 并补充 JUnit 5 单元/集成测试，执行 `mvn test` 验证全量通过。
