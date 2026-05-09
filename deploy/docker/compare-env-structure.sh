#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
用法:
  compare-env-structure.sh [--ignore-status] <left_env_file> <right_env_file> [left_label] [right_label]

说明:
  - 比较两个 env 文件的键集合，默认同时比较相同键的 SET/EMPTY 状态。
  - 只对安全白名单里的键打印实际值差异，避免在日志中泄露 secret。
  - 出现键缺失 / 多余，或 SET/EMPTY 状态不一致时，脚本返回非 0。

示例:
  ./deploy/docker/compare-env-structure.sh /data/deploy/status-mvp-price-backend/prod.env /secure/dev.env prod dev
  ./deploy/docker/compare-env-structure.sh --ignore-status env.example /data/deploy/status-mvp-price-backend/prod.env env.example prod
EOF
}

IGNORE_STATUS=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ignore-status)
      IGNORE_STATUS=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      printf '[compare-env-structure] 未知参数: %s\n' "$1" >&2
      usage >&2
      exit 1
      ;;
    *)
      break
      ;;
  esac
done

LEFT_FILE="${1:?缺少 left_env_file}"
RIGHT_FILE="${2:?缺少 right_env_file}"
LEFT_LABEL="${3:-left}"
RIGHT_LABEL="${4:-right}"

python3 - "$LEFT_FILE" "$RIGHT_FILE" "$LEFT_LABEL" "$RIGHT_LABEL" "$IGNORE_STATUS" <<'PY'
import json
import sys
from pathlib import Path

left_file = Path(sys.argv[1])
right_file = Path(sys.argv[2])
left_label = sys.argv[3]
right_label = sys.argv[4]
ignore_status = sys.argv[5].lower() == "true"

SAFE_VALUE_KEYS = {
    "APP_LINKS_ANDROID_PACKAGE_NAME",
    "APP_LINKS_ANDROID_SHA256_CERT_FINGERPRINTS",
    "APP_LINKS_IOS_APP_IDS",
    "APP_LINKS_PATH_PREFIX",
    "APP_UPDATE_ANDROID_CHANNEL",
    "APP_UPDATE_ANDROID_ENABLED",
    "APP_UPDATE_ANDROID_MANIFEST_CACHE_TTL_SECONDS",
    "APP_UPDATE_ANDROID_MANIFEST_URL",
    "APP_UPDATE_ANDROID_PACKAGE_NAME",
    "AUTH_ANDROID_PROTECTED_AUTH_ALLOWED_CHANNELS",
    "AUTH_APPLE_AUDIENCES",
    "AUTH_APPLE_ENABLED",
    "AUTH_APP_REDIRECT_ALLOWLIST",
    "AUTH_ONE_TIME_CODE_TTL_SECONDS",
    "AUTH_PUBLIC_BASE_URL",
    "AUTH_SOCIAL_ENABLED",
    "AUTH_TG_ISSUER",
    "AUTH_TG_REDIRECT_URI",
    "AUTH_TG_SCOPES",
    "AUTH_X_REDIRECT_URI",
    "AUTH_X_SCOPES",
    "BRIDGE_ACROSS_ALLOWED_CHAIN_IDS",
    "BRIDGE_ACROSS_ALLOWED_TOKEN_SYMBOLS",
    "BRIDGE_ACROSS_ALLOWLIST_MODE",
    "BRIDGE_ACROSS_API_BASE_URL",
    "BRIDGE_ACROSS_CHAINS_CACHE_TTL_SECONDS",
    "BRIDGE_ACROSS_ROUTES_CACHE_TTL_SECONDS",
    "BRIDGE_ACROSS_TIMEOUT_MS",
    "BRIDGE_ACROSS_TOKEN_ALLOWLIST_MODE",
    "COINGECKO_ALLOW_PUBLIC",
    "CORS_ALLOWED_ORIGINS",
    "JUPITER_LITE_API_BASE_URL",
    "JUPITER_RL_IP_LIMIT",
    "JUPITER_RL_WINDOW_SECONDS",
    "JUPITER_TIMEOUT_MS",
    "LEGAL_APP_NAME",
    "LEGAL_CONTACT_ADDRESS",
    "LEGAL_CONTACT_EMAIL",
    "LEGAL_EFFECTIVE_DATE",
    "LEGAL_ENTITY_NAME",
    "LEGAL_GOVERNING_LAW",
    "LEGAL_GOVERNING_LAW_ZH",
    "LEGAL_PRIVACY_URL",
    "LEGAL_SUPPORT_URL",
    "LEGAL_TERMS_URL",
    "PORT",
    "PORTFOLIO_DEFAULT_CHAIN_IDS",
    "PORTFOLIO_REQUEST_TTL_SECONDS",
    "PORTFOLIO_TIMEOUT_MS",
    "PRICE_CACHE_TTL_SECONDS",
    "REDIS_URL",
    "REQUEST_CACHE_TTL_SECONDS",
    "SAFE_NOTIFICATIONS_ENABLED",
    "SAFE_NOTIFICATIONS_INBOX_LIMIT",
    "SAFE_NOTIFICATIONS_POLL_DELAY_MS",
    "SAFE_NOTIFICATIONS_PULL_DEFAULT_LIMIT",
    "SAFE_NOTIFICATIONS_QUEUE_TTL_SECONDS",
    "SAFE_TX_GW_RL_DEVICE_LIMIT",
    "SAFE_TX_GW_RL_IP_LIMIT",
    "SAFE_TX_GW_RL_WINDOW_SECONDS",
    "SAFE_TX_PROXY_ENABLED",
    "SAFE_TX_SERVICE_BASE_URL",
    "SAFE_TX_SERVICE_TIMEOUT_MS",
    "SAFE_TX_UPSTREAM_BURST",
    "SAFE_TX_UPSTREAM_RPS",
    "UNISWAP_API_BASE_URL",
    "UNISWAP_RL_IP_LIMIT",
    "UNISWAP_RL_WINDOW_SECONDS",
    "UNISWAP_TIMEOUT_MS",
}

def fail(message: str) -> None:
    raise SystemExit(f"[compare-env-structure] {message}")

def parse_env(path: Path) -> dict[str, str]:
    if not path.exists():
        fail(f"未找到 env 文件: {path}")

    parsed: dict[str, str] = {}
    for lineno, line in enumerate(path.read_text().splitlines(), start=1):
        raw = line.strip()
        if not raw or raw.startswith("#"):
            continue
        if "=" not in raw:
            fail(f"{path}:{lineno} 不是合法的 KEY=VALUE 行")
        key, value = raw.split("=", 1)
        key = key.strip()
        if not key:
            fail(f"{path}:{lineno} 存在空 KEY")
        if key in parsed:
            fail(f"{path}:{lineno} 出现重复 KEY: {key}")
        parsed[key] = value
    return parsed

def value_status(value: str) -> str:
    return "EMPTY" if value == "" else "SET"

left_payload = parse_env(left_file)
right_payload = parse_env(right_file)

left_only = sorted(set(left_payload) - set(right_payload))
right_only = sorted(set(right_payload) - set(left_payload))

status_mismatch: list[tuple[str, str, str]] = []
safe_value_diff: list[tuple[str, str, str]] = []

for key in sorted(set(left_payload) & set(right_payload)):
    left_status = value_status(left_payload[key])
    right_status = value_status(right_payload[key])
    if left_status != right_status:
        status_mismatch.append((key, left_status, right_status))
        continue
    if key in SAFE_VALUE_KEYS and left_payload[key] != right_payload[key]:
        safe_value_diff.append((key, left_payload[key], right_payload[key]))

print(
    "[compare-env-structure] "
    f"{left_label}_keys={len(left_payload)} "
    f"{right_label}_keys={len(right_payload)} "
    f"ignore_status={'true' if ignore_status else 'false'}"
)
print(
    "[compare-env-structure] "
    f"only_in_{left_label}={json.dumps(left_only, ensure_ascii=False)}"
)
print(
    "[compare-env-structure] "
    f"only_in_{right_label}={json.dumps(right_only, ensure_ascii=False)}"
)

if status_mismatch:
    status_payload = [
        {
            "key": key,
            left_label: left_status,
            right_label: right_status,
        }
        for key, left_status, right_status in status_mismatch
    ]
    print(
        "[compare-env-structure] "
        f"status_mismatch={json.dumps(status_payload, ensure_ascii=False)}"
    )
else:
    print("[compare-env-structure] status_mismatch=[]")

if safe_value_diff:
    safe_payload = [
        {
            "key": key,
            left_label: left_value,
            right_label: right_value,
        }
        for key, left_value, right_value in safe_value_diff
    ]
    print(
        "[compare-env-structure] "
        f"safe_value_diff={json.dumps(safe_payload, ensure_ascii=False)}"
    )
else:
    print("[compare-env-structure] safe_value_diff=[]")

has_structural_diff = bool(left_only or right_only or (status_mismatch and not ignore_status))
if has_structural_diff:
    raise SystemExit(1)
PY
