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
├── .github/workflows/
│   └── docker-publish.yml           # GitHub Actions 自动测试、构建并推送 GHCR 多架构镜像
├── AGENTS.md                        # 全局项目约定与 AI 协作规范
├── Dockerfile                       # 多阶段轻量级 JDK 17 镜像构建配置
├── .dockerignore                    # Docker 构建上下文忽略规则
├── docker-compose.yml               # 基于 GHCR 镜像的 Docker Compose 部署模板
├── README.md                        # 全局项目文档（本文件）
├── pom.xml                          # Maven 构建配置
└── src/
    ├── main/
    │   ├── java/com/muse/connect/
    │   │   ├── MuseAnyConnectApplication.java
    │   │   ├── aidoc/               # 面向注解的 AI 文档引擎 (@AiApiGroup / @AiEndpoint / @AiField)
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
- **管理控制台 (Thymeleaf)**：`http://localhost:8080/`（开启登录保护后自动跳转 `/login`）
- **在线调试台 (Playground)**：`http://localhost:8080/playground`
- **公开健康检查**：`http://localhost:8080/health`
- **运行状态与 H2 内存统计**：`http://localhost:8080/api/stats`

### 可选环境变量 / 配置项 (`application.yml`)

| 配置项 | 环境变量 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `server.port` | `SERVER_PORT` | `8080` | HTTP 服务端口 |
| `muse.auth.username` | `MUSE_ADMIN_USERNAME` | `admin` | 控制台初始管理员账号 |
| `muse.auth.password` | `MUSE_ADMIN_PASSWORD` | `123456`（兜底默认值） | 控制台初始管理员密码（未配置或留空时兜底为 `123456`；启动时自动用 PBKDF2-HMAC-SHA256 哈希并清除内存明文） |
| `muse.auth.password-hash` | `MUSE_ADMIN_PASSWORD_HASH` | `""` | 可选：直接注入预计算的 PBKDF2 哈希（`pbkdf2$<iter>$<saltB64>$<hashB64>`），优先级高于明文密码 |
| `muse.auth.session-ttl-minutes` | `MUSE_AUTH_SESSION_TTL_MINUTES` | `720` | 管理员登录会话有效期（分钟，默认 12 小时） |
| `muse.auth.max-failed-attempts` | `MUSE_AUTH_MAX_FAILED_ATTEMPTS` | `5` | 单个 IP 连续登录失败触发锁定的阈值 |
| `muse.auth.lockout-minutes` | `MUSE_AUTH_LOCKOUT_MINUTES` | `15` | 触发防爆破锁定后的冷却时间（分钟） |
| `muse.auth.allow-raw-export` | `MUSE_ALLOW_RAW_EXPORT` | `true` | 是否允许导出含明文凭据的连接器 JSON（生产可设为 `false` 禁用） |
| `muse.relay.api-key` | `MUSE_RELAY_API_KEY` | `123456789`（兜底默认值） | 机器调用 `/api/**` 请求头 `X-Muse-Key` 校验密钥（未配置或留空时兜底为 `123456789`） |
| `muse.relay.default-timeout-ms` | `MUSE_DEFAULT_TIMEOUT_MS` | `15000` | 外部请求默认超时（毫秒） |
| `muse.relay.activity-buffer-size` | `MUSE_ACTIVITY_BUFFER_SIZE` | `100` | JVM 环形日志最大条数 |

#### 登录权限与安全机制说明
- **零 H2 侵入**：登录账号、PBKDF2 密码哈希、有界内存会话（上限 64 条）与 IP 防爆破计数均独立驻留 JVM 内存，不向 H2 写入任何用户或会话表，容器重启后所有历史会话自动失效。
- **双轨鉴权**：
  - 配置 `MUSE_ADMIN_PASSWORD`（或 `MUSE_ADMIN_PASSWORD_HASH`）后，控制台页面（`/`、`/connectors/**`、`/playground`）与 `/api/**` 均受保护；未登录访问页面自动跳转 `/login`，未认证调用 `/api/**` 返回 `401`。
  - 同时配置 `MUSE_RELAY_API_KEY` 时，外部机器/Muse 通过请求头 `X-Muse-Key` 访问 `/api/**`，管理员登录后可在浏览器 Playground 直接调试。
- **纵深防护**：Cookie 强制 `HttpOnly` + `SameSite=Lax`（反代 HTTPS 下自动开启 `Secure`），所有 Web 变更表单与导出接口强制校验 CSRF Token，并内置按 IP 登录防爆破锁定与安全响应头。

### Docker 与 GitHub Actions (GHCR) 自动构建部署

每次推送代码到 `main` 分支（或推送 `v*.*.*` 标签）时，GitHub Actions（`.github/workflows/docker-publish.yml`）会自动：
1. 执行 `mvn test` 验证全部单元测试与集成测试；
2. 构建多架构镜像（`linux/amd64` + `linux/arm64`）并推送至 GitHub Container Registry（GHCR）：
   - 镜像地址：`ghcr.io/stanxu-symple/museanyconnect:latest`
   - 同时生成 `main`、`sha-<commit>` 以及语义化版本标签。

#### 直接拉取并运行

```bash
docker pull ghcr.io/stanxu-symple/museanyconnect:latest

docker run -d \
  --name muse-any-connect \
  -p 8080:8080 \
  -e MUSE_ADMIN_USERNAME="admin" \
  -e MUSE_ADMIN_PASSWORD="change-me-strong-password" \
  -e MUSE_RELAY_API_KEY="your-secret-key" \
  --restart unless-stopped \
  ghcr.io/stanxu-symple/museanyconnect:latest
```

或使用项目自带的 `docker-compose.yml`：

```bash
docker compose pull
docker compose up -d
```

> **提示（首次拉取权限）**：GHCR 首次由 Actions 推送生成的包默认可能是私有状态。如需免登录直接 `docker pull`，请打开 GitHub 仓库右侧 **Packages** → 点击 `museanyconnect` → **Package settings** → **Change visibility** 设为 **Public**；若保持私有，需先用带 `read:packages` 权限的 PAT 执行 `echo "<PAT>" | docker login ghcr.io -u StanXu-symple --password-stdin`。

#### 本地自行构建镜像

```bash
docker build -t muse-any-connect:local .
docker run -d --name muse-any-connect -p 8080:8080 muse-any-connect:local
```

---

## 4. 连接器配置与认证方式

> 控制台的新建 / 编辑连接器页面已内置各平台与各认证方式的**分步获取教程**（切换应用类型与认证方式时自动联动展示）。

### 4.1 X (Twitter)
- **认证方式 (`authMode`)**：
  - `BEARER_TOKEN`：使用 Bearer Token 调用 X API v2（App-Only Bearer Token 仅读公开数据；OAuth 2.0 User Access Token 支持读写，默认 2 小时过期）。
  - `OAUTH1A`：配置 `apiKey`、`apiSecret`、`accessToken`、`accessTokenSecret`，中转站自动生成 HMAC-SHA1 `Authorization: OAuth ...` 请求头（**推荐长期自动发推**，凭证不会自动过期）。
- **如何获取**：
  - **App-Only Bearer Token**：登录 `developer.x.com/en/portal/dashboard` → 打开 App → **Keys and tokens** → **Bearer Token** → **Generate**。
  - **OAuth 2.0 User Access Token (PKCE)**：在 **Settings → User authentication settings** 开启 `Read and write` 与 Callback URI → 记录 `Client ID` → 通过 `https://x.com/i/oauth2/authorize` 授权拿 `code` → 调 `POST https://api.x.com/2/oauth2/token` 换取 `access_token`。
  - **OAuth 1.0a 四元组**：先在 **User authentication settings** 将 **App permissions** 设为 `Read and write` 并保存 → 到 **Keys and tokens** 生成/复制 **Consumer Keys**（API Key & Secret）与 **Access Token and Secret**（确认标注 `Created with Read and Write permissions`，若此前已生成过须点击 **Regenerate**）。
- **支持字段**：`apiBaseUrl`（默认 `https://api.x.com`）、`bearerToken`、`apiKey`、`apiSecret`、`accessToken`、`accessTokenSecret`、`proxyHost`、`proxyPort`、`timeoutMs`。

### 4.2 Telegram
- **认证方式 (`authMode`)**：
  - `BOT_TOKEN`：标准 Bot API 模式，自动拼接 `https://api.telegram.org/bot<botToken>/<method>`。
- **如何获取**：在 Telegram 联系 `@BotFather` 发送 `/newbot` 获取 Bot Token；向机器人发消息后调用 `GET /updates` 从 `message.chat.id` 获取私聊或群组 Chat ID。
- **支持字段**：`apiBaseUrl`（默认 `https://api.telegram.org`）、`botToken`、`defaultChatId`、`parseMode`（`MarkdownV2` / `HTML` / 空）、`proxyHost`、`proxyPort`、`timeoutMs`。

### 4.3 SSH 远程服务器
- **认证方式 (`authMode`)**：
  - `PASSWORD`：`host`、`port`、`username`、`password`。
  - `PRIVATE_KEY`：`host`、`port`、`username`、`privateKey`（PEM / OpenSSH 私钥文本，可通过 `ssh-keygen -t ed25519` 生成并将 `.pub` 部署到目标机 `~/.ssh/authorized_keys`）、可选 `passphrase`。
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

### 5.5 AI 接口文档与注解扩展 (`/api/ai/**` & `/ai-docs`)

本项目内置面向注解编程的动态 AI 文档引擎（零外部依赖），自动扫描 Spring MVC 控制器上标注了 `@AiApiGroup` / `@AiEndpoint` / `@AiField` 的接口，并结合运行时已启用的连接器清单（严格脱敏、零密钥输出）生成 AI 可读文档：

- `GET /api/ai/llms.txt` — 动态生成 Markdown 格式 AI 调用指南（支持 `?onlyActive=true` 仅输出当前已启用连接器对应的接口分组）。
- `GET /api/ai/catalog` — 返回同源结构化 JSON 目录（含已启用连接器元信息与反射出的接口参数定义）。
- `GET /ai-docs` — 控制台可视化预览页，支持筛选与一键复制给 AI。

**后续新增接口只需在 `AiDocMeta` 枚举中登记条目并在控制器加注解，无需改动文档生成器**：
```java
@AiApiGroup(AiDocMeta.GROUP_X_RELAY)
@RestController
@RequestMapping("/api/relay/x/{profileId}")
public class MyController {

    @AiEndpoint(AiDocMeta.EP_X_POST_TWEET)
    @PostMapping("/custom")
    public RelayResponse custom(
            @PathVariable @AiField(AiDocMeta.FIELD_X_PROFILE_ID) String profileId,
            @RequestBody MyRequest req
    ) { ... }
}
```

---

## 6. 安全提示

1. 生产环境或公网部署时，务必同时设置 `MUSE_ADMIN_USERNAME` + `MUSE_ADMIN_PASSWORD`（或 `MUSE_ADMIN_PASSWORD_HASH`）与 `MUSE_RELAY_API_KEY`，并配合 HTTPS / 内网网关（如 Tailscale、Nginx TLS）使用。
2. 建议为 SSH 连接配置最小权限专用系统账号，并利用 `allowedCommandPrefixes` 限制可执行命令范围。
3. 控制台界面与普通查询接口默认脱敏密钥（`****`）；如需彻底禁止导出明文凭据，可将环境变量 `MUSE_ALLOW_RAW_EXPORT` 设为 `false`。
