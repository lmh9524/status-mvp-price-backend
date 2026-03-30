#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:?用法: prod-smoke.sh <base_url>}"
DEVICE_ID="${SMOKE_DEVICE_ID:-prod-release-smoke}"
APP_REDIRECT_URI="${APP_REDIRECT_URI:-veilwallet://openlogin}"
ANDROID_VERSION_CODE="${ANDROID_VERSION_CODE:-5}"
ANDROID_PACKAGE_NAME="${ANDROID_PACKAGE_NAME:-com.statusmvp}"
ANDROID_CHANNEL="${ANDROID_CHANNEL:-official}"

log() {
  printf '[prod-smoke] %s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf '[prod-smoke] 缺少命令: %s\n' "$1" >&2
    exit 1
  }
}

assert_http_ok_or_redirect() {
  local url="$1"
  local label="$2"
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' "$url")"
  if [[ "$code" != "200" && "$code" != "302" ]]; then
    printf '[prod-smoke] %s 失败: %s 返回 HTTP %s\n' "$label" "$url" "$code" >&2
    exit 1
  fi
  log "$label 通过: HTTP $code"
}

assert_json_shape() {
  local json_payload="$1"
  local label="$2"
  local python_check="$3"

  JSON_PAYLOAD="$json_payload" python3 - "$label" "$python_check" <<'PY'
import json
import os
import sys

label = sys.argv[1]
python_check = sys.argv[2]
payload = json.loads(os.environ["JSON_PAYLOAD"])
namespace = {"payload": payload}
exec(python_check, namespace)
print(f"[prod-smoke] {label} 通过")
PY
}

require_command curl
require_command python3

log "开始检查: $BASE_URL"

health_payload="$(curl -fsS "$BASE_URL/health")"
[[ "$health_payload" == '{"ok":true}' ]] || {
  printf '[prod-smoke] /health 返回异常: %s\n' "$health_payload" >&2
  exit 1
}
log "/health 通过"

actuator_payload="$(curl -fsS "$BASE_URL/actuator/health")"
assert_json_shape \
  "$actuator_payload" \
  "/actuator/health" \
  "assert payload.get('status') == 'UP', payload"

jwks_payload="$(curl -fsS "$BASE_URL/.well-known/jwks.json")"
assert_json_shape \
  "$jwks_payload" \
  "/.well-known/jwks.json" \
  "assert isinstance(payload.get('keys'), list) and payload['keys'], payload"

app_update_payload="$(curl -fsS --get \
  --data-urlencode "versionCode=$ANDROID_VERSION_CODE" \
  --data-urlencode "packageName=$ANDROID_PACKAGE_NAME" \
  --data-urlencode "channel=$ANDROID_CHANNEL" \
  "$BASE_URL/api/v1/app/android/update")"
assert_json_shape \
  "$app_update_payload" \
  "/api/v1/app/android/update" \
  "assert 'hasUpdate' in payload and 'latestVersionCode' in payload and 'required' in payload, payload"

x_start_payload="$(curl -fsS -H "X-Device-Id: $DEVICE_ID" --get \
  --data-urlencode "appRedirectUri=$APP_REDIRECT_URI" \
  "$BASE_URL/api/v1/auth/x/start")"
assert_json_shape \
  "$x_start_payload" \
  "/api/v1/auth/x/start" \
  "assert payload.get('authorizeUrl') and payload.get('state') and payload.get('expiresInSeconds', 0) > 0, payload"

tg_start_payload="$(curl -fsS -H "X-Device-Id: $DEVICE_ID" --get \
  --data-urlencode "appRedirectUri=$APP_REDIRECT_URI" \
  "$BASE_URL/api/v1/auth/tg/start")"
assert_json_shape \
  "$tg_start_payload" \
  "/api/v1/auth/tg/start" \
  "assert payload.get('authorizeUrl') and payload.get('state') and payload.get('expiresInSeconds', 0) > 0, payload"

assert_http_ok_or_redirect "$BASE_URL/terms" "/terms"
assert_http_ok_or_redirect "$BASE_URL/privacy" "/privacy"
assert_http_ok_or_redirect "$BASE_URL/support" "/support"

log "全部 smoke check 通过"
