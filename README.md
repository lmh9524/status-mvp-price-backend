# Status MVP Backend

这个仓库虽然沿用了 `status-mvp-price-backend` 的名称，但当前职责已经不只是价格服务，而是 VeilWallet 移动端的后端平台层。

它目前承载以下能力：

- 代币价格聚合与投资组合接口
- Android 应用版本更新检查接口
- Across 支持链路目录代理
- Solana Jupiter Quote / Swap 代理
- 社交登录、SIWE、应用 JWT、DApp 同步
- Safe 协作查询、通知注册与拉取
- Safe Tx Service 代理与 Gateway
- 法律页面、健康检查、Prometheus 指标

技术栈：

- Java 17
- Spring Boot 3.3.7
- Spring WebFlux
- Redis
- Maven

## 快速开始

### 前置要求

- JDK 17
- Maven
- Redis 7+
- Docker（推荐，本地起 Redis 和容器部署都更方便）

### 1. 准备环境变量

```bash
cp env.example env.local
```

`env.example` 只适合本地开发起步，不适合作为生产配置直接使用。至少需要在真实环境中覆盖下面几类配置：

- API Key / OAuth Secret
- `AUTH_APP_JWT_SECRET`
- Web3Auth / X / Telegram / Apple 相关密钥
- `CORS_ALLOWED_ORIGINS`
- Safe Tx Service API Key

### 2. Docker 方式启动

```bash
docker compose --env-file env.local up --build
```

默认监听：

- `http://127.0.0.1:${PORT}`

### 3. Maven 方式启动

先把所需环境变量注入当前 shell，然后运行：

```bash
mvn spring-boot:run
```

如果你是从整个工作区根目录联调，也可以使用根目录的 `dev-backend-local.ps1`，它会帮你检查 Redis、补默认开发环境变量并启动服务。

## 当前模块

### 价格与资产

- `GET /api/v1/prices`
- `GET /api/v1/prices/by-contract`
- `GET /api/v1/portfolio`
- `GET /api/v1/portfolio/snapshot`

价格源优先级当前为：

1. CoinGecko Pro
2. CoinMarketCap
3. Binance
4. 稳定币兜底
5. VEILX 链上定价

### Android App 更新检查

- `GET /api/v1/app/android/update`

用于给 Android 客户端返回最新版本、最低支持版本、是否强更、APK 下载地址和校验信息。

推荐接法：

1. 用移动端仓库里的 `scripts/publish-android-update.js` 生成 `android-update.json` 和 APK 发布产物
2. 把 `android-update.json` 与 APK 挂到 HTTPS 静态地址
3. 在后端配置 `APP_UPDATE_ANDROID_MANIFEST_URL`
4. 客户端请求 `/api/v1/app/android/update?versionCode=...&packageName=...&channel=official`

### Across 桥接目录

- `GET /api/v1/bridge/across/directory`

用于把 Across 支持链、支持代币和 allowlist 下发给移动端，减少频繁发版。

默认建议配置为：

- 固定 `BRIDGE_ACROSS_ALLOWED_CHAIN_IDS`
- `BRIDGE_ACROSS_ALLOWLIST_MODE=STRICT`
- `BRIDGE_ACROSS_TOKEN_ALLOWLIST_MODE=ALL_ON_ALLOWED_CHAINS`

这样可以保持链范围稳定，同时让固定链上的 Across 可桥接币种随目录自动扩容。

### Solana Jupiter 代理

- `GET /api/v1/solana/jupiter/quote`
- `POST /api/v1/solana/jupiter/swap`

用于移动网络或特定运营商环境下的稳定访问、超时控制与限流。

### Auth / Session / DApp Sync

常用接口分组如下：

- OAuth 起始与回调：`/api/v1/auth/x/start`、`/api/v1/auth/x/callback`、`/api/v1/auth/tg/start`、`/api/v1/auth/tg/callback`
- Telegram / Apple 登录：`/api/v1/auth/tg/login`、`/api/v1/auth/apple/login`
- 交换与续期：`/api/v1/auth/exchange`、`/api/v1/auth/refresh`
- 应用态查询：`/api/v1/auth/me`
- Provider 绑定：`/api/v1/auth/providers/bind`、`/api/v1/auth/providers/unbind`
- DApp 同步：`/api/v1/auth/sync/dapps`
- SIWE：`/api/v1/auth/siwe/nonce`、`/api/v1/auth/siwe/verify`
- Web3Auth JWT：`/api/v1/auth/web3auth/jwt`
- JWKS：`/.well-known/jwks.json`

### Safe 协作与通知

- 协作查询：`POST /api/v1/safe/collaboration/discovery/query`
- Inbox 查询：`POST /api/v1/safe/collaboration/inbox/query`
- 通知注册：`POST /api/v1/safe/notifications/v1/register`
- 通知拉取：`POST /api/v1/safe/notifications/v1/pull`
- 清理订阅：`POST /api/v1/safe/notifications/v1/subscriptions/delete-all`
- 删除设备：`DELETE /api/v1/safe/notifications/v1/devices/{deviceUuid}`

### Safe Tx Service

当前保留两套访问形态：

- 简化代理：`/api/v1/safe/tx-service/...`
- Gateway 兼容路径：`/api/v1/safe/tx-service-gateway/{chain}/...`

移动端 Safe 模块优先通过后端访问上游 Safe Tx Service，这样更容易统一鉴权、限流、超时和后续切换策略。

### 健康 / 指标 / 法律页

- `GET /health`
- `GET /actuator/prometheus`
- `GET /terms`
- `GET /privacy`
- `GET /support`

## 关键环境变量

### 运行时基础配置

- `PORT`
- `REDIS_URL`
- `CORS_ALLOWED_ORIGINS`

生产环境禁止把 `CORS_ALLOWED_ORIGINS` 保持为 `*`。

### 价格服务

- `COINGECKO_PRO_API_KEY`
- `COINMARKETCAP_API_KEY`
- `COINGECKO_ALLOW_PUBLIC`
- `COINGECKO_SYMBOL_ID_OVERRIDES`
- `BSC_RPC_URL`
- `VEILX_CONTRACT_ADDRESS`

### App 更新

- `APP_UPDATE_ANDROID_ENABLED`
- `APP_UPDATE_ANDROID_CHANNEL`
- `APP_UPDATE_ANDROID_PACKAGE_NAME`
- `APP_UPDATE_ANDROID_MANIFEST_URL`
- `APP_UPDATE_ANDROID_MANIFEST_CACHE_TTL_SECONDS`
- `APP_UPDATE_ANDROID_LATEST_VERSION_CODE`
- `APP_UPDATE_ANDROID_LATEST_VERSION_NAME`
- `APP_UPDATE_ANDROID_MIN_SUPPORTED_VERSION_CODE`
- `APP_UPDATE_ANDROID_REQUIRED`
- `APP_UPDATE_ANDROID_DOWNLOAD_URL`
- `APP_UPDATE_ANDROID_SHA256`
- `APP_UPDATE_ANDROID_FILE_SIZE_BYTES`
- `APP_UPDATE_ANDROID_RELEASE_NOTES`
- `APP_UPDATE_ANDROID_PUBLISHED_AT`

推荐优先使用 `APP_UPDATE_ANDROID_MANIFEST_URL`。如果它为空，后端会退回读取内联的 `APP_UPDATE_ANDROID_*` 版本字段。

### Across / Jupiter

- `BRIDGE_ACROSS_*`
- `JUPITER_*`

这两组配置主要控制：

- 上游 API 地址
- 超时
- 允许的链 / 代币范围
- 缓存 TTL
- 速率限制

Across 相关常用配置：

- `BRIDGE_ACROSS_ALLOWLIST_MODE`
- `BRIDGE_ACROSS_TOKEN_ALLOWLIST_MODE`
- `BRIDGE_ACROSS_ALLOWED_CHAIN_IDS`
- `BRIDGE_ACROSS_ALLOWED_TOKEN_SYMBOLS`

其中 `BRIDGE_ACROSS_ALLOWED_TOKEN_SYMBOLS` 只在 `BRIDGE_ACROSS_TOKEN_ALLOWLIST_MODE=STRICT` 时生效。

### Auth

- `AUTH_ENABLED`
- `AUTH_APP_JWT_SECRET`
- `AUTH_PUBLIC_BASE_URL`
- `AUTH_APP_REDIRECT_ALLOWLIST`
- `AUTH_WEB3AUTH_*`
- `AUTH_X_*`
- `AUTH_TG_*`
- `AUTH_APPLE_*`
- `AUTH_RISK_*`

### Safe

- `SAFE_NOTIFICATIONS_*`
- `APP_SAFE_NOTIFICATIONS_REMOTE_FCM_*`
- `APP_SAFE_NOTIFICATIONS_REMOTE_APNS_*`
- `SAFE_TX_SERVICE_*`
- `SAFE_TX_PROXY_ENABLED`
- `SAFE_TX_GW_RL_*`

## 目录结构

```text
src/main/java/io/statusmvp/pricebackend/
  auth/        登录、JWT、SIWE、绑定与同步
  client/      外部 API / 上游服务访问
  config/      Spring 配置
  controller/  HTTP 入口
  model/       请求响应模型
  service/     业务编排
src/main/resources/
  application.yml
Dockerfile
docker-compose.yml
env.example
```

## 开发与运维约定

- 所有移动端可见接口统一走 `/api/v1` 前缀，历史 `/health` 与法律页除外
- 外部 API 调用必须有超时、限流和降级策略
- Redis 不可用时，服务应尽量降级为无缓存而不是直接不可启动
- 不要把第三方 API Key、JWT Secret、Safe Tx Service Key 放进移动端
- 默认日志中不要输出完整 token、完整 secret 或完整上游凭据
- 新增代理类接口时，优先补 Prometheus 指标或至少预留可观测性埋点

## 部署提示

仓库已提供：

- `docker-compose.yml`
- `Dockerfile`
- `env.example`
- `deploy/docker/restore-prod-env-from-backup.sh`
- `deploy/docker/compare-env-with-live.sh`
- `deploy/docker/package-release-source.sh`
- `deploy/docker/prod-smoke.sh`
- `deploy/docker/prod-release.sh`

推荐部署方式：

1. 本地 / 测试环境优先使用 Docker Compose
2. 服务器 / 生产环境统一使用 Docker 部署，不再维护 systemd 部署链路
3. 生产环境把真实密钥放在仓库外的独立 env 文件中，通过 `docker compose --env-file /path/to/status-mvp-price-backend.env up -d --build` 启动
4. 如果 Redis 由外部提供，只启动 `price-backend` 服务，并把 `REDIS_URL` 指向外部 Redis
5. 生产环境显式配置 CORS、Auth Secret、OAuth Secret、推送密钥与 Safe 上游密钥

推荐正式发布流程：

1. 生产 ENV 文件单独放在仓库外，例如 `/data/deploy/status-mvp-price-backend/prod.env`
2. 把 `deploy/docker/*.sh` 同步到服务器独立部署目录，例如 `/data/deploy/status-mvp-price-backend/bin`
3. 如果历史配置原来在 systemd 的 `/etc/status-mvp-price-backend/status-mvp-price-backend.env`，先用 `/data/deploy/status-mvp-price-backend/bin/restore-prod-env-from-backup.sh <backup.tar.gz> /data/deploy/status-mvp-price-backend/prod.env` 恢复到 Docker 专用路径
4. 发布前先执行 `/data/deploy/status-mvp-price-backend/bin/compare-env-with-live.sh /data/deploy/status-mvp-price-backend/prod.env`，确认外部 env 与当前线上容器零差异
5. 在本地干净仓库执行 `./deploy/docker/package-release-source.sh` 生成源码快照，或直接使用 CI 产物
6. 把源码快照上传到服务器临时目录并解压，例如 `/tmp/status-mvp-price-backend-<git-sha>`
7. 在服务器上执行 `BUILD_CONTEXT=/tmp/status-mvp-price-backend-<git-sha> PROD_ENV_FILE=/data/deploy/status-mvp-price-backend/prod.env /data/deploy/status-mvp-price-backend/bin/prod-release.sh`
8. 脚本会自动完成 Redis 保护性快照、候选容器启动、关键 smoke check、正式切换与回滚点保留
9. 如果 smoke check 或正式容器健康检查失败，脚本会自动回滚到切换前的旧容器

不要把生产机上的业务仓库工作树当成正式发布源。生产发布应始终来自本地干净仓库或 CI 构建产物，避免服务器上的脏工作树、分叉提交或临时调试文件污染正式发布。

## 相关说明

- 移动端仓库：`../VeilWallet-mobile`
- Root 工作区的开发便捷脚本位于上一层目录
- 更细的排查流程、模块边界与协作规则以项目根目录 `AGENTS.md` 和 `.agent/skills/` 为准
