#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_BUILD_CONTEXT="$(cd "$SCRIPT_DIR/../.." 2>/dev/null && pwd || true)"

APP_NAME="${APP_NAME:-status-mvp-price-backend}"
LIVE_NAME="${LIVE_NAME:-status-mvp-price-backend}"
ENV_FILE="${PROD_ENV_FILE:-/data/deploy/status-mvp-price-backend/prod.env}"
BACKUP_DIR="${BACKUP_DIR:-/data/deploy/status-mvp-price-backend/backups}"
PUBLIC_HEALTH_URL="${PUBLIC_HEALTH_URL:-https://vex.veilx.global/health}"
CANDIDATE_PORT="${CANDIDATE_PORT:-3303}"
LIVE_PORT="${LIVE_PORT:-3003}"
WAIT_SECONDS="${WAIT_SECONDS:-45}"
SMOKE_DEVICE_ID="${SMOKE_DEVICE_ID:-prod-release-smoke}"
APP_REDIRECT_URI="${APP_REDIRECT_URI:-veilwalletw3a://openlogin}"
ANDROID_VERSION_CODE="${ANDROID_VERSION_CODE:-5}"
ANDROID_PACKAGE_NAME="${ANDROID_PACKAGE_NAME:-com.statusmvp}"
ANDROID_CHANNEL="${ANDROID_CHANNEL:-official}"
SMOKE_SOCIAL_AUTH="${SMOKE_SOCIAL_AUTH:-false}"
BUILD_CONTEXT="${BUILD_CONTEXT:-${SOURCE_DIR:-$DEFAULT_BUILD_CONTEXT}}"
SOURCE_LABEL="${SOURCE_LABEL:-}"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
CANDIDATE_NAME="${APP_NAME}-candidate-${TIMESTAMP}"
PREVIOUS_NAME="${APP_NAME}-prev-${TIMESTAMP}"

log() {
  printf '[prod-release] %s\n' "$*"
}

fail() {
  printf '[prod-release] %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1"
}

resolve_source_ref() {
  if [[ -n "$SOURCE_LABEL" ]]; then
    printf '%s\n' "$SOURCE_LABEL"
    return 0
  fi

  if [[ -d "$BUILD_CONTEXT/.git" ]] || git -C "$BUILD_CONTEXT" rev-parse --git-dir >/dev/null 2>&1; then
    git -C "$BUILD_CONTEXT" rev-parse --short HEAD 2>/dev/null && return 0
  fi

  printf 'snapshot\n'
}

wait_for_health() {
  local url="$1"
  local label="$2"
  local attempt

  for attempt in $(seq 1 "$WAIT_SECONDS"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "$label 已就绪: $url"
      return 0
    fi
    sleep 1
  done

  fail "$label 未在 ${WAIT_SECONDS}s 内通过健康检查: $url"
}

env_get() {
  local key="$1"
  python3 - "$ENV_FILE" "$key" <<'PY'
from pathlib import Path
import sys

env_file = Path(sys.argv[1])
key = sys.argv[2]
for line in env_file.read_text().splitlines():
    raw = line.strip()
    if not raw or raw.startswith("#") or "=" not in raw:
        continue
    current_key, value = raw.split("=", 1)
    if current_key == key:
        print(value)
        break
PY
}

env_truthy() {
  case "${1,,}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

csv_contains() {
  local csv="$1"
  local expected="$2"
  python3 - "$csv" "$expected" <<'PY'
import sys

values = [item.strip() for item in sys.argv[1].split(",") if item.strip()]
expected = sys.argv[2].strip()
raise SystemExit(0 if expected in values else 1)
PY
}

validate_security_env() {
  local auth_social_enabled auth_public_base_url auth_app_redirect_allowlist
  local auth_web3auth_private_key_pem auth_x_enabled auth_x_redirect_uri
  local auth_tg_enabled auth_tg_redirect_uri auth_android_play_integrity_enabled
  local auth_android_channels play_integrity_package play_integrity_email play_integrity_key
  local expected_https_redirect expected_x_redirect expected_tg_redirect

  auth_social_enabled="$(env_get AUTH_SOCIAL_ENABLED)"
  auth_public_base_url="$(env_get AUTH_PUBLIC_BASE_URL)"
  auth_app_redirect_allowlist="$(env_get AUTH_APP_REDIRECT_ALLOWLIST)"
  auth_web3auth_private_key_pem="$(env_get AUTH_WEB3AUTH_PRIVATE_KEY_PEM)"
  auth_x_enabled="$(env_get AUTH_X_ENABLED)"
  auth_x_redirect_uri="$(env_get AUTH_X_REDIRECT_URI)"
  auth_tg_enabled="$(env_get AUTH_TG_ENABLED)"
  auth_tg_redirect_uri="$(env_get AUTH_TG_REDIRECT_URI)"
  auth_android_play_integrity_enabled="$(env_get AUTH_ANDROID_PLAY_INTEGRITY_ENABLED)"
  auth_android_channels="$(env_get AUTH_ANDROID_PROTECTED_AUTH_ALLOWED_CHANNELS)"
  play_integrity_package="$(env_get AUTH_ANDROID_PLAY_INTEGRITY_PACKAGE_NAME)"
  play_integrity_email="$(env_get AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL)"
  play_integrity_key="$(env_get AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_PEM)"

  if env_truthy "$auth_social_enabled"; then
    [[ -n "$auth_public_base_url" ]] || fail "AUTH_SOCIAL_ENABLED=true 时必须配置 AUTH_PUBLIC_BASE_URL"
    expected_https_redirect="${auth_public_base_url%/}/openlogin"
    csv_contains "$auth_app_redirect_allowlist" "$expected_https_redirect" || fail "AUTH_APP_REDIRECT_ALLOWLIST 必须包含 ${expected_https_redirect}"
    csv_contains "$auth_app_redirect_allowlist" "veilwalletw3a://openlogin" || fail "AUTH_APP_REDIRECT_ALLOWLIST 必须包含 veilwalletw3a://openlogin"
    [[ -n "$auth_web3auth_private_key_pem" ]] || fail "AUTH_SOCIAL_ENABLED=true 时必须配置 AUTH_WEB3AUTH_PRIVATE_KEY_PEM"

    if env_truthy "${auth_x_enabled:-true}"; then
      expected_x_redirect="${auth_public_base_url%/}/api/v1/auth/x/callback"
      [[ "$auth_x_redirect_uri" == "$expected_x_redirect" ]] || fail "AUTH_X_REDIRECT_URI 必须等于 ${expected_x_redirect}"
    fi

    if env_truthy "${auth_tg_enabled:-true}"; then
      expected_tg_redirect="${auth_public_base_url%/}/api/v1/auth/tg/login"
      [[ "$auth_tg_redirect_uri" == "$expected_tg_redirect" ]] || fail "AUTH_TG_REDIRECT_URI 必须等于 ${expected_tg_redirect}"
    fi
  fi

  if env_truthy "$auth_android_play_integrity_enabled" && csv_contains "${auth_android_channels:-}" "play"; then
    [[ -n "$play_integrity_package" ]] || fail "启用 Play Integrity 时必须配置 AUTH_ANDROID_PLAY_INTEGRITY_PACKAGE_NAME"
    [[ -n "$play_integrity_email" ]] || fail "启用 Play Integrity 时必须配置 AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL"
    [[ -n "$play_integrity_key" ]] || fail "启用 Play Integrity 时必须配置 AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_PEM"
  fi

  log "生产安全环境预检通过"
}

backup_redis_snapshot() {
  local redis_url="$1"
  local lastsave_before lastsave_after redis_dir redis_dbfile snapshot_path target_path

  [[ -n "$redis_url" ]] || fail "ENV 文件缺少 REDIS_URL，无法做 Redis 保护性备份"

  lastsave_before="$(redis-cli -u "$redis_url" LASTSAVE)"
  log "触发 Redis BGSAVE，确保发布前数据已有最新快照"
  redis-cli -u "$redis_url" BGSAVE >/dev/null 2>&1 || true

  for _ in $(seq 1 30); do
    lastsave_after="$(redis-cli -u "$redis_url" LASTSAVE)"
    if [[ "$lastsave_after" != "$lastsave_before" ]]; then
      break
    fi
    sleep 1
  done

  redis_dir="$(redis-cli -u "$redis_url" CONFIG GET dir | tail -n 1)"
  redis_dbfile="$(redis-cli -u "$redis_url" CONFIG GET dbfilename | tail -n 1)"
  snapshot_path="${redis_dir}/${redis_dbfile}"
  target_path="${BACKUP_DIR}/${APP_NAME}-redis-${TIMESTAMP}.rdb"

  sudo cp "$snapshot_path" "$target_path"
  log "Redis 快照已备份: $target_path"
}

run_smoke() {
  local base_url="$1"

  SMOKE_DEVICE_ID="$SMOKE_DEVICE_ID" \
    APP_REDIRECT_URI="$APP_REDIRECT_URI" \
    ANDROID_VERSION_CODE="$ANDROID_VERSION_CODE" \
    ANDROID_PACKAGE_NAME="$ANDROID_PACKAGE_NAME" \
    ANDROID_CHANNEL="$ANDROID_CHANNEL" \
    SMOKE_SOCIAL_AUTH="$SMOKE_SOCIAL_AUTH" \
    "$SCRIPT_DIR/prod-smoke.sh" "$base_url"
}

cleanup_candidate() {
  docker rm -f "$CANDIDATE_NAME" >/dev/null 2>&1 || true
}

rollback_live() {
  set +e
  cleanup_candidate
  if docker ps -a --format '{{.Names}}' | grep -Fxq "$LIVE_NAME"; then
    docker rm -f "$LIVE_NAME" >/dev/null 2>&1 || true
  fi
  if docker ps -a --format '{{.Names}}' | grep -Fxq "$PREVIOUS_NAME"; then
    docker rename "$PREVIOUS_NAME" "$LIVE_NAME" >/dev/null 2>&1 || true
    docker start "$LIVE_NAME" >/dev/null 2>&1 || true
    log "已回滚到旧容器: $LIVE_NAME"
  fi
}

require_command docker
require_command curl
require_command python3
require_command redis-cli
require_command sudo

[[ -f "$ENV_FILE" ]] || fail "未找到生产 ENV 文件: $ENV_FILE"
[[ -n "$BUILD_CONTEXT" ]] || fail "未指定构建上下文，请设置 BUILD_CONTEXT 或 SOURCE_DIR"
[[ -f "$BUILD_CONTEXT/Dockerfile" ]] || fail "构建上下文缺少 Dockerfile: $BUILD_CONTEXT"
validate_security_env
mkdir -p "$BACKUP_DIR"
cp "$ENV_FILE" "$BACKUP_DIR/${APP_NAME}-env-${TIMESTAMP}.bak"
log "ENV 文件已备份: $BACKUP_DIR/${APP_NAME}-env-${TIMESTAMP}.bak"

REDIS_URL="$(env_get REDIS_URL)"
backup_redis_snapshot "$REDIS_URL"

SOURCE_REF="$(resolve_source_ref)"
IMAGE_TAG="${IMAGE_TAG:-${APP_NAME}:${SOURCE_REF}-${TIMESTAMP}}"
log "构建上下文: $BUILD_CONTEXT"
log "发布来源标识: $SOURCE_REF"
log "开始构建镜像: $IMAGE_TAG"
docker build -t "$IMAGE_TAG" "$BUILD_CONTEXT"

cleanup_candidate
log "启动候选容器，先在 ${CANDIDATE_PORT} 端口做预发布验证"
docker run -d \
  --name "$CANDIDATE_NAME" \
  --network host \
  --env-file "$ENV_FILE" \
  -e PORT="$CANDIDATE_PORT" \
  "$IMAGE_TAG" >/dev/null

wait_for_health "http://127.0.0.1:${CANDIDATE_PORT}/health" "候选容器"
run_smoke "http://127.0.0.1:${CANDIDATE_PORT}"

trap rollback_live ERR INT TERM

if docker ps -a --format '{{.Names}}' | grep -Fxq "$LIVE_NAME"; then
  log "保留旧容器为回滚点: $PREVIOUS_NAME"
  docker stop "$LIVE_NAME" >/dev/null
  docker rename "$LIVE_NAME" "$PREVIOUS_NAME"
fi

log "切换正式容器到新镜像"
docker run -d \
  --name "$LIVE_NAME" \
  --network host \
  --restart unless-stopped \
  --env-file "$ENV_FILE" \
  -e PORT="$LIVE_PORT" \
  "$IMAGE_TAG" >/dev/null

wait_for_health "http://127.0.0.1:${LIVE_PORT}/health" "正式容器"
run_smoke "http://127.0.0.1:${LIVE_PORT}"

if [[ -n "$PUBLIC_HEALTH_URL" ]]; then
  curl -fsS "$PUBLIC_HEALTH_URL" >/dev/null
  log "公网健康检查通过: $PUBLIC_HEALTH_URL"
fi

trap - ERR INT TERM
cleanup_candidate

log "正式发布完成"
log "当前镜像: $IMAGE_TAG"
log "回滚容器保留为: $PREVIOUS_NAME"
