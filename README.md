# MuseAnyConnect — Muse HTTP 连接器中转站

**MuseAnyConnect** 是专为 **Muse** 设计的轻量级 HTTP 中转服务（Relay Gateway），基于 **JDK 17 + Spring Boot 3.5 + H2 内存数据库 + Thymeleaf** 构建，将外部平台与远程主机能力统一封装为标准 HTTP REST 接口：

- **X (Twitter) 连接器**：支持 Bearer Token（OAuth 2.0 App-Only / User Token）与 OAuth 1.0a（HMAC-SHA1 自动签名），支持通用 `/2/**` API 转发以及发推、搜索、用户查询等快捷接口。
- **Telegram 连接器**：支持 Bot API Token 与可选代理转发，支持快捷发送消息、拉取更新以及任意 Bot API 方法透传。
- **SSH 远程服务器连接器**：基于 JSch 支持密码认证与私钥（RSA / Ed25519 / ECDSA）认证，提供 HTTP 命令执行与 SFTP 文件读写接口。
- **Thymeleaf 管理控制台**：轻量级服务端渲染界面，无需 Node/前端构建，支持连接配置增删改查、连通性测试、配置 JSON 导入/导出、在线调试（Playground）与 curl 生成，以及实时 H2 内存占用监控。

---

## 1. 核心架构与技术选型

| 组件 | 选型 | 说明 |
| :--- | :--- | :--- |
| 运行时 | JDK 17 | 使用原生 `java.net.http.HttpClient`，零额外 HTTP 客户端依赖 |
| 框架 | Spring Boot 3.5.11 | Web + Thymeleaf + JDBC（不使用 JPA/Hibernate 以降低内存占用） |
| 内存数据库 | H2 (`mem:museconn`) | 仅存核心连接配置，极致压缩缓存与内存行上限 |
| SSH 引擎 | `com.github.mwiede:jsch` 0.2.21 | 轻量单包，支持现代 OpenSSH 密钥算法 |
| 前端界面 | Thymeleaf + 原生 CSS/JS | 零构建步骤，开箱即用 |

### 目录结构

```text
museAnyConnect/
├── AGENTS.md                        # 全局项目约定与 AI 协作规范
├── README.md                        # 全局项目文档（本文件）
├── pom.xml                          # Maven 构建配置
└── src/
    ├── main/
    │   ├── java/com/muse/connect/
    │   │   ├── MuseAnyConnectApplication.java
    │   │   ├── config/              # 配置属性、HttpClient 工厂、Relay 鉴权过滤器
    │   │   ├── controller/          # Thymeleaf 视图控制器 & REST 中转接口
    │   │   ├── model/               # 枚举、连接配置模型、统一响应模型
    │   │   ├── repository/          # Spring JDBC 仓库 & JVM 环形日志缓冲区
    │   │   └── service/             # X / Telegram / SSH 中转与签名核心逻辑
    │   └── resources/
    │       ├── application.yml      # 服务配置与 H2 内存压缩参数
    │       ├── schema.sql           # 极简单表 DDL
    │       ├── static/css/app.css   # 控制台样式
    │       └── templates/           # Thymeleaf 页面模板
    └── test/                        # 单元测试与集成测试
```

---

## 2. H2 内存数据库压缩策略（核心设计）

为满足**尽量压缩内存数据库使用**的要求，本项目采取以下优化措施：

1. **移除 JPA / Hibernate**：直接使用 `spring-boot-starter-jdbc` (`JdbcTemplate`)，避免 ORM 元数据、一级缓存、动态代理和额外连接池开销。
2. **H2 参数极简调优**：
   ```text
   jdbc:h2:mem:museconn;DB_CLOSE_DELAY=-1;CACHE_SIZE=1024;MAX_MEMORY_ROWS=100;COMPRESS=TRUE;WRITE_DELAY=0
   ```
   - `CACHE_SIZE=1024`：将 H2 页缓存限制为 **1 MB**（默认通常为 16 MB 或堆内存百分比）。
   - `MAX_MEMORY_ROWS=100`：严格限制常驻内存结果集行数。
   - `COMPRESS=TRUE`：启用 H2 内部数据压缩。
   - `HikariCP` 连接池收紧为 `maximum-pool-size=3`、`minimum-idle=1`。
3. **单表极简 Schema**：H2 中仅持久化 `connector_profile` 一张小表，每条连接配置序列化为紧凑 JSON（限制 4KB 以内），**不在 H2 中存储任何调用日志、审计流水或大二进制对象**。
4. **调用日志走 JVM 定长环形缓冲区**：最近 100 条请求记录存放在 `RelayActivityBuffer`（`ConcurrentLinkedDeque` 定长淘汰），H2 内存不会随调用次数增长。
5. **无状态导入/导出**：由于 H2 运行在纯内存模式，进程重启后会清空；控制台和 API 提供 JSON 导出/导入（以及可选启动预置配置），兼顾零磁盘依赖与配置可迁移性。

---

## 3. 快速启动

### 环境要求
- JDK 17+
- Maven 3.8+

### 启动命令

```bash
mvn spring-boot:run
```

或打包运行：

```bash
mvn clean package -DskipTests
java -Xms32m -Xmx128m -jar target/muse-any-connect-0.1.0-SNAPSHOT.jar
```

启动后访问：
- **管理控制台 (Thymeleaf)**：`http://localhost:8080/`
- **在线调试台 (Playground)**：`http://localhost:8080/playground`
- **健康检查**：`http://localhost:8080/api/stats`

### 可选环境变量 / 配置项 (`application.yml`)

| 配置项 | 环境变量 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `server.port` | `SERVER_PORT` | `8080` | HTTP 服务端口 |
| `muse.relay.api-key` | `MUSE_RELAY_API_KEY` | `""`（空表示不校验） | 若设置，调用 `/api/**` 需携带请求头 `X-Muse-Key` |
| `muse.relay.default-timeout-ms` | `MUSE_DEFAULT_TIMEOUT_MS` | `15000` | 外部请求默认超时（毫秒） |
| `muse.relay.activity-buffer-size` | `MUSE_ACTIVITY_BUFFER_SIZE` | `100` | JVM 环形日志最大条数 |

---

## 4. 连接器配置与认证方式

### 4.1 X (Twitter)
- **认证方式 (`authMode`)**：
  - `BEARER_TOKEN`：使用 Bearer Token 调用 X API v2（读推文、搜索、查用户或 OAuth 2.0 User Token 发推）。
  - `OAUTH1A`：配置 `apiKey`、`apiSecret`、`accessToken`、`accessTokenSecret`，中转站自动生成 HMAC-SHA1 `Authorization: OAuth ...` 请求头。
- **支持字段**：`apiBaseUrl`（默认 `https://api.x.com`）、`bearerToken`、`apiKey`、`apiSecret`、`accessToken`、`accessTokenSecret`、`proxyHost`、`proxyPort`、`timeoutMs`。

### 4.2 Telegram
- **认证方式 (`authMode`)**：
  - `BOT_TOKEN`：标准 Bot API 模式，自动拼接 `https://api.telegram.org/bot<botToken>/<method>`。
- **支持字段**：`apiBaseUrl`（默认 `https://api.telegram.org`）、`botToken`、`defaultChatId`、`parseMode`（`MarkdownV2` / `HTML` / 空）、`proxyHost`、`proxyPort`、`timeoutMs`。

### 4.3 SSH 远程服务器
- **认证方式 (`authMode`)**：
  - `PASSWORD`：`host`、`port`、`username`、`password`。
  - `PRIVATE_KEY`：`host`、`port`、`username`、`privateKey`（PEM / OpenSSH 私钥文本）、可选 `passphrase`。
- **安全与附加字段**：`timeoutMs`、`strictHostKeyChecking`（默认 `false`）、`allowedCommandPrefixes`（可选逗号分隔命令白名单前缀，例如 `ls,df,uname,docker ps`；留空则允许所有命令）。

---

## 5. Muse HTTP 中转接口规范 (`/api/relay/**`)

> 若配置了 `muse.relay.api-key`，所有 `/api/**` 请求需附带请求头：`X-Muse-Key: <your-key>`。

### 5.1 连接器管理 (`/api/connectors`)
- `GET /api/connectors` — 获取所有连接器（敏感密钥已脱敏）
- `GET /api/connectors/{id}` — 获取单个连接器详情（脱敏）
- `POST /api/connectors` — 新增或更新连接器配置
- `DELETE /api/connectors/{id}` — 删除连接器
- `POST /api/connectors/{id}/test` — 执行连通性测试
- `GET /api/connectors/export` — 导出全部连接器 JSON
- `POST /api/connectors/import` — 批量导入连接器 JSON
- `GET /api/stats` — 查看运行状态、调用计数及 H2 内存占用

### 5.2 X 中转接口 (`/api/relay/x/{profileId}`)
- `POST /api/relay/x/{profileId}/request` — 通用 X API 转发
  ```json
  {
    "method": "GET",
    "path": "/2/tweets/search/recent",
    "queryParams": { "query": "spring boot", "max_results": "10" },
    "body": null
  }
  ```
- `POST /api/relay/x/{profileId}/tweet` — 快捷发推
  ```json
  {
    "text": "Hello from MuseAnyConnect!",
    "replyToTweetId": null
  }
  ```
- `GET /api/relay/x/{profileId}/search?query=...&maxResults=10` — 快捷近期推文搜索
- `GET /api/relay/x/{profileId}/users/by/username/{username}` — 快捷用户信息查询

### 5.3 Telegram 中转接口 (`/api/relay/telegram/{profileId}`)
- `POST /api/relay/telegram/{profileId}/send` — 快捷发送文本消息
  ```json
  {
    "chatId": "123456789",
    "text": "Muse notification",
    "parseMode": "HTML",
    "disableNotification": false
  }
  ```
- `GET /api/relay/telegram/{profileId}/updates?offset=0&limit=20&timeout=0` — 拉取消息更新
- `POST /api/relay/telegram/{profileId}/method/{method}` — 通用 Bot API 方法透传（如 `getMe`、`sendPhoto`、`getChat`）

### 5.4 SSH 中转接口 (`/api/relay/ssh/{profileId}`)
- `POST /api/relay/ssh/{profileId}/exec` — 远程执行命令
  ```json
  {
    "command": "uname -a && uptime",
    "timeoutMs": 10000
  }
  ```
- `POST /api/relay/ssh/{profileId}/sftp/read` — 读取远程文本文件
  ```json
  {
    "remotePath": "/etc/hostname",
    "maxBytes": 65536
  }
  ```
- `POST /api/relay/ssh/{profileId}/sftp/write` — 写入远程文本文件
  ```json
  {
    "remotePath": "/tmp/muse-note.txt",
    "content": "hello from muse",
    "append": false
  }
  ```

---

## 6. 安全提示

1. 生产环境或公网部署时，务必设置 `MUSE_RELAY_API_KEY` 并配合 HTTPS / 内网网关（如 Tailscale、Nginx TLS）使用。
2. 建议为 SSH 连接配置最小权限专用系统账号，并利用 `allowedCommandPrefixes` 限制可执行命令范围。
3. 控制台界面与导出 JSON 中的密钥字段在普通查看接口默认脱敏（`****`），仅在明确导出或提交更新时处理明文。
