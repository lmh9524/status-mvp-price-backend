# Status MVP Backend

这个仓库虽然沿用了 `status-mvp-price-backend` 的名称，但当前职责已经不只是价格服务，而是 VeilWallet 移动端的后端平台层。

它目前承载以下能力：

- 代币价格聚合与投资组合接口
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

### Across 桥接目录

- `GET /api/v1/bridge/across/directory`

用于把 Across 支持链、支持代币和 allowlist 下发给移动端，减少频繁发版。

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

### Across / Jupiter

- `BRIDGE_ACROSS_*`
- `JUPITER_*`

这两组配置主要控制：

- 上游 API 地址
- 超时
- 允许的链 / 代币范围
- 缓存 TTL
- 速率限制

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
deploy/
  systemd/
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
- `deploy/systemd/`

推荐部署方式：

1. 本地 / 测试环境优先使用 Docker Compose
2. 服务器长期运行优先使用 systemd + 外部 env 文件
3. 生产环境显式配置 CORS、Auth Secret、OAuth Secret、推送密钥与 Safe 上游密钥

## 相关说明

- 移动端仓库：`../VeilWallet-mobile`
- Root 工作区的开发便捷脚本位于上一层目录
- 更细的排查流程、模块边界与协作规则以项目根目录 `AGENTS.md` 和 `.agent/skills/` 为准
