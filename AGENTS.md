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
- **全中文注释规范**：项目中所有注释（包括 Java Javadoc 与行内注释、测试代码注释、HTML/CSS/JS 注释、SQL/YAML/Dockerfile/配置文件注释）必须统一使用中文编写；专有名词、协议名称或代码标识符可保留英文原词。禁止新增纯英文注释（Dockerfile 语法指令 `# syntax=...`、脚本 Shebang `#!/bin/sh` 等工具声明除外）。
- 所有对外返回的连接配置 DTO 必须对敏感字段（`bearerToken`, `apiSecret`, `accessTokenSecret`, `authToken`, `ct0`, `botToken`, `password`, `privateKey`, `passphrase`）进行脱敏；编辑更新时，若前端提交占位符或空值，应保留原库中已存密钥（除非用户明确清除/替换）。
- 所有外部调用（X / Telegram / SSH）必须具备明确的超时控制，禁止无限阻塞线程。
- 新增接口或功能后，同步更新 `README.md` 并补充 JUnit 5 单元/集成测试，执行 `mvn test` 验证全量通过。

## 4. 常量集中管理规范（`*Constants` 分类设计思路）
为消除业务代码中的魔法字符串、保证路由/错误文案/协议字段的单一事实来源（Single Source of Truth）并提升可维护性，项目中**禁止在业务源码（`src/main/java`）中直接散落硬编码字符串**，必须按职责与类型统一收口至 `com.muse.connect.constant` 包下的 `*Constants` 常量类（静态常量 `public static final`）：

### 4.1 常量类设计原则
1. **命名与结构**：统一放在 `com.muse.connect.constant` 包下，类名以 `Constants` 结尾，声明为 `public final class`，提供私有构造方法防止实例化，并配备完整中文 Javadoc 与分组注释。
2. **编译期常量优先**：用于 `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@DeleteMapping`、`@ModelAttribute`、`@RequestParam(defaultValue = ...)`、`@Pattern`、`@NotBlank`、`@ConfigurationProperties(prefix = ...)` 等注解的字符串，必须定义为编译期常量（`public static final String`），以直接支持注解属性引用。
3. **单一事实来源与向后兼容**：业务类（如 `RelayAuthFilter`、`ConnectorConfig`、`AppProperties` 等）如对外暴露了既有公共常量，可保留为指向对应 `*Constants` 字段的常量别名，避免破坏已有调用方。
4. **结构化元数据例外**：`@AiApiGroup` / `@AiEndpoint` / `@AiField` 所需的复合结构元数据（标题 + 说明 + 类型 + 排序 + 必填 + 示例）统一集中管理在枚举 `com.muse.connect.aidoc.AiDocMeta` 中，与 `*Constants` 体系互补，禁止在 Controller 注解中直接硬编码文案。

### 4.2 按类型划分的 `*Constants` 职责矩阵
新增硬编码字符串前，必须按所属类型归入对应常量类；若出现全新的独立业务域/协议，可按同等命名规范新建对应的 `*Constants` 类：

| 常量类 | 归属类型与职责范围 |
| :--- | :--- |
| `RouteConstants` | 所有 `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@DeleteMapping` 路径、安全放行路径前缀、重定向地址、Thymeleaf 视图名及请求参数默认值 |
| `ExceptionMessageConstants` | 所有 `ConnectorException` / `IllegalArgumentException` / `IllegalStateException` 异常消息、Bean Validation 注解校验提示、鉴权失败响应消息及中转失败提示文案 |
| `SecurityConstants` | API Key/管理员默认回退凭据、鉴权与 CSRF 请求头/参数/Cookie/Request 属性名、安全响应头键值、PBKDF2 算法与编码格式常量、敏感字段脱敏占位符 |
| `HttpConstants` | HTTP 方法名、标准 HTTP 请求头与媒体类型（MIME）、代理缓存键、OAuth 1.0a 签名参数与哈希算法名称、通用 URL/摘要分隔符 |
| `WebViewConstants` | Thymeleaf `Model` / `RedirectAttributes` 属性键、控制层 JSON 响应字段名、控制台页面成功/状态提示消息模板、时间格式与校验分隔符 |
| `StorageConstants` | Spring 配置属性前缀与键名、SQLite JDBC URL 前缀与列名、所有 SQL 语句常量及连接器 ID 正则/生成规则 |
| `ConnectorLabelConstants` | `ConnectorType` 与 `AuthMode` 枚举的展示名称标签 |
| `LogMessageConstants` | SLF4J 日志输出消息模板（`log.info` / `log.warn` 等） |
| `XRelayConstants` | X (Twitter) API v2 与网页 Cookie 接口路径、默认公共 Bearer Token / User-Agent、GraphQL 操作名与默认 Query ID、请求体字段、特性开关列表及 `x-client-transaction-id` 解析正则 |
| `TelegramRelayConstants` | Telegram Bot API 基础地址、路径前缀、方法名、方法名校验正则及请求/响应 JSON 字段名 |
| `SshRelayConstants` | SSH / SFTP 通道类型、JSch 配置键值、连通性测试命令、命令白名单拦截操作符列表、响应 JSON 字段名及流水动作前缀 |
| `AiDocConstants` | AI 文档扫描器 Bean 名、目录 JSON 字段键、Markdown (`llms.txt`) 渲染模板片段、连接器约束摘要文案及反射类型名称 |
