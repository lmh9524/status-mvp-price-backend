#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
用法:
  render-env-candidate.sh <reference_env_file> <current_env_file> <example_env_file> <output_env_file> [reference_label] [current_label]

说明:
  - 以 reference env 为目标结构，生成 current 环境的候选 env 文件。
  - 已存在于 current env 的键默认保留 current 值，避免未审核前直接改变当前环境行为。
  - reference env 中缺失于 current env 的非敏感键会自动补齐。
  - reference env 中缺失于 current env 的敏感 / 环境特定键不会直接继承，会留空并在日志中列出待人工补充项。
  - current env 里仅存在于运行时或仅等于 env.example 默认值的噪声键会被自动剔除。
  - 可选环境变量 `CANDIDATE_PROXY_IPS_HINT` 可额外补充当前环境可信代理 IP，例如 `172.18.0.1`。

示例:
  ./deploy/docker/render-env-candidate.sh /secure/prod.env /secure/dev.env env.example /tmp/dev.candidate.env prod dev
EOF
}

if [[ $# -lt 4 ]]; then
  usage >&2
  exit 1
fi

REFERENCE_FILE="$1"
CURRENT_FILE="$2"
EXAMPLE_FILE="$3"
OUTPUT_FILE="$4"
REFERENCE_LABEL="${5:-reference}"
CURRENT_LABEL="${6:-current}"

python3 - "$REFERENCE_FILE" "$CURRENT_FILE" "$EXAMPLE_FILE" "$OUTPUT_FILE" "$REFERENCE_LABEL" "$CURRENT_LABEL" <<'PY'
import re
import sys
from collections import OrderedDict
from pathlib import Path

reference_file = Path(sys.argv[1])
current_file = Path(sys.argv[2])
example_file = Path(sys.argv[3])
output_file = Path(sys.argv[4])
reference_label = sys.argv[5]
current_label = sys.argv[6]
proxy_ips_hint = [item.strip() for item in __import__("os").environ.get("CANDIDATE_PROXY_IPS_HINT", "").split(",") if item.strip()]

RUNTIME_NOISE_KEYS = {
    "JAVA_HOME",
    "JAVA_VERSION",
    "LANG",
    "LANGUAGE",
    "LC_ALL",
    "PATH",
}

ENV_SPECIFIC_KEYS = {
    "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_BUNDLE_ID",
    "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_USE_SANDBOX",
    "APP_SAFE_NOTIFICATIONS_REMOTE_FCM_PROJECT_ID",
    "APP_UPDATE_ANDROID_MANIFEST_URL",
    "AUTH_APPLE_AUDIENCES",
    "AUTH_APP_REDIRECT_ALLOWLIST",
    "AUTH_PUBLIC_BASE_URL",
    "AUTH_RISK_TRUSTED_PROXY_IPS",
    "AUTH_TG_REDIRECT_URI",
    "AUTH_X_REDIRECT_URI",
    "REDIS_URL",
}

PREFER_CURRENT_DEFAULT_KEYS = {
    "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_ENABLED",
    "APP_SAFE_NOTIFICATIONS_REMOTE_FCM_ENABLED",
    "APP_UPDATE_ANDROID_ENABLED",
}

SENSITIVE_EXACT_KEYS = {
    "ANKR_API_KEY",
    "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_PRIVATE_KEY_PEM",
    "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_TEAM_ID",
    "APP_SAFE_NOTIFICATIONS_REMOTE_FCM_CLIENT_EMAIL",
    "APP_SAFE_NOTIFICATIONS_REMOTE_FCM_PRIVATE_KEY_PEM",
    "AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL",
    "AUTH_ANDROID_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_PEM",
    "AUTH_APP_JWT_SECRET",
    "AUTH_TG_BOT_TOKEN",
    "AUTH_TG_CLIENT_ID",
    "AUTH_TG_CLIENT_SECRET",
    "AUTH_TG_STATE_SECRET",
    "AUTH_WEB3AUTH_CLIENT_ID",
    "AUTH_WEB3AUTH_CLIENT_SECRET",
    "AUTH_WEB3AUTH_PRIVATE_KEY_PEM",
    "AUTH_X_CLIENT_ID",
    "AUTH_X_CLIENT_SECRET",
    "AUTH_X_STATE_SECRET",
    "COINGECKO_PRO_API_KEY",
    "COINMARKETCAP_API_KEY",
    "SAFE_TX_SERVICE_API_KEY",
    "UNISWAP_API_KEY",
}

SENSITIVE_PATTERNS = [
    re.compile(pattern)
    for pattern in [
        r".*_API_KEY$",
        r".*_BOT_TOKEN$",
        r".*_CLIENT_EMAIL$",
        r".*_CLIENT_SECRET$",
        r".*_JWT_SECRET$",
        r".*_PRIVATE_KEY_PEM$",
        r".*_SECRET$",
        r".*_SERVICE_ACCOUNT_EMAIL$",
        r".*_STATE_SECRET$",
        r".*_TOKEN$",
    ]
]

def fail(message: str) -> None:
    raise SystemExit(f"[render-env-candidate] {message}")

def parse_env(path: Path) -> OrderedDict[str, str]:
    if not path.exists():
        fail(f"未找到 env 文件: {path}")

    payload: OrderedDict[str, str] = OrderedDict()
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
        if key in payload:
            fail(f"{path}:{lineno} 出现重复 KEY: {key}")
        payload[key] = value
    return payload

def is_sensitive(key: str) -> bool:
    if key in SENSITIVE_EXACT_KEYS:
        return True
    return any(pattern.fullmatch(key) for pattern in SENSITIVE_PATTERNS)

def is_truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}

def csv_union(*parts: str) -> str:
    seen: list[str] = []
    for part in parts:
        for item in part.split(","):
            normalized = item.strip()
            if normalized and normalized not in seen:
                seen.append(normalized)
    return ",".join(seen)

def derive_from_context(
    key: str,
    current_payload: OrderedDict[str, str],
    example_payload: OrderedDict[str, str],
) -> str | None:
    public_base_url = current_payload.get("AUTH_PUBLIC_BASE_URL", "").strip().rstrip("/")
    if key == "AUTH_X_REDIRECT_URI":
        if not public_base_url:
            return None
        return f"{public_base_url}/api/v1/auth/x/callback"
    if key == "AUTH_TG_REDIRECT_URI":
        if not public_base_url:
            return None
        return f"{public_base_url}/api/v1/auth/tg/login"
    if key == "AUTH_APPLE_AUDIENCES":
        return example_payload.get("AUTH_APPLE_AUDIENCES")
    if key == "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_BUNDLE_ID":
        return example_payload.get("APP_SAFE_NOTIFICATIONS_REMOTE_APNS_BUNDLE_ID")
    if key == "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_USE_SANDBOX":
        return example_payload.get("APP_SAFE_NOTIFICATIONS_REMOTE_APNS_USE_SANDBOX")
    if key == "AUTH_RISK_TRUSTED_PROXY_IPS":
        base = example_payload.get("AUTH_RISK_TRUSTED_PROXY_IPS", "")
        return csv_union(base, ",".join(proxy_ips_hint))
    return None

def should_require_manual_fill(key: str, candidate_payload: OrderedDict[str, str]) -> bool:
    if key in {
        "AUTH_X_CLIENT_ID",
        "AUTH_X_CLIENT_SECRET",
        "AUTH_X_REDIRECT_URI",
        "AUTH_X_STATE_SECRET",
        "AUTH_TG_BOT_TOKEN",
        "AUTH_TG_CLIENT_ID",
        "AUTH_TG_CLIENT_SECRET",
        "AUTH_TG_REDIRECT_URI",
        "AUTH_TG_STATE_SECRET",
        "AUTH_WEB3AUTH_CLIENT_ID",
        "AUTH_WEB3AUTH_CLIENT_SECRET",
        "AUTH_WEB3AUTH_PRIVATE_KEY_PEM",
        "AUTH_APPLE_AUDIENCES",
    }:
        return is_truthy(candidate_payload.get("AUTH_SOCIAL_ENABLED"))

    if key in {
        "APP_SAFE_NOTIFICATIONS_REMOTE_FCM_PROJECT_ID",
        "APP_SAFE_NOTIFICATIONS_REMOTE_FCM_CLIENT_EMAIL",
        "APP_SAFE_NOTIFICATIONS_REMOTE_FCM_PRIVATE_KEY_PEM",
    }:
        return is_truthy(candidate_payload.get("APP_SAFE_NOTIFICATIONS_REMOTE_FCM_ENABLED"))

    if key in {
        "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_BUNDLE_ID",
        "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_USE_SANDBOX",
        "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_TEAM_ID",
        "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_KEY_ID",
        "APP_SAFE_NOTIFICATIONS_REMOTE_APNS_PRIVATE_KEY_PEM",
    }:
        return is_truthy(candidate_payload.get("APP_SAFE_NOTIFICATIONS_REMOTE_APNS_ENABLED"))

    if key == "APP_UPDATE_ANDROID_MANIFEST_URL":
        return is_truthy(candidate_payload.get("APP_UPDATE_ANDROID_ENABLED"))

    return True

reference = parse_env(reference_file)
current = parse_env(current_file)
example = parse_env(example_file)

candidate: OrderedDict[str, str] = OrderedDict()
candidate_sources: OrderedDict[str, str] = OrderedDict()
manual_fill_keys: list[str] = []
copied_from_reference: list[str] = []
kept_from_current: list[str] = []
derived_from_current_context: list[str] = []
dropped_runtime_noise: list[str] = []
dropped_current_defaults: list[str] = []
kept_current_review: list[str] = []
diff_from_reference: list[str] = []

for key, reference_value in reference.items():
    if key in current:
        current_value = current[key]
        candidate[key] = current_value
        candidate_sources[key] = f"{current_label}:existing"
        kept_from_current.append(key)
        if current_value != reference_value:
            diff_from_reference.append(key)
        continue

    if key in PREFER_CURRENT_DEFAULT_KEYS and key in example:
        candidate[key] = example[key]
        candidate_sources[key] = "example:preserve_current_default"
        copied_from_reference.append(f"{key}<=example_default")
        continue

    derived_value = derive_from_context(key, current, example)
    if derived_value is not None:
        candidate[key] = derived_value
        candidate_sources[key] = "derived_from_current_context"
        derived_from_current_context.append(key)
        continue

    if key in ENV_SPECIFIC_KEYS or is_sensitive(key):
        candidate[key] = ""
        candidate_sources[key] = "manual_fill_required"
        continue

    candidate[key] = reference_value
    candidate_sources[key] = f"{reference_label}:copied"
    copied_from_reference.append(key)

for key, current_value in current.items():
    if key in reference:
        continue
    if key in RUNTIME_NOISE_KEYS:
        dropped_runtime_noise.append(key)
        continue
    if key in example and example[key] == current_value:
        dropped_current_defaults.append(key)
        continue

    candidate[key] = current_value
    candidate_sources[key] = f"{current_label}:review_extra"
    kept_current_review.append(key)

for key, source in candidate_sources.items():
    if source != "manual_fill_required":
        continue
    if should_require_manual_fill(key, candidate):
        manual_fill_keys.append(key)

output_file.parent.mkdir(parents=True, exist_ok=True)
header_lines = [
    "# GENERATED CANDIDATE ENV FILE",
    f"# reference={reference_file}",
    f"# current={current_file}",
    f"# example={example_file}",
    "# DO NOT APPLY DIRECTLY BEFORE REVIEWING manual_fill_required KEYS.",
    "",
]

body_lines = [f"{key}={value}" for key, value in candidate.items()]
output_file.write_text("\n".join(header_lines + body_lines) + "\n")

def render(items: list[str]) -> str:
    if not items:
        return "[]"
    quoted = ", ".join(f'"{item}"' for item in items)
    return f"[{quoted}]"

print(
    "[render-env-candidate] "
    f"reference_keys={len(reference)} "
    f"current_keys={len(current)} "
    f"candidate_keys={len(candidate)}"
)
print(
    "[render-env-candidate] "
    f"kept_from_{current_label}={render(kept_from_current)}"
)
print(
    "[render-env-candidate] "
    f"copied_from_{reference_label}={render(copied_from_reference)}"
)
print(
    "[render-env-candidate] "
    f"derived_from_{current_label}_context={render(derived_from_current_context)}"
)
print(
    "[render-env-candidate] "
    f"manual_fill_required={render(manual_fill_keys)}"
)
print(
    "[render-env-candidate] "
    f"dropped_runtime_noise={render(dropped_runtime_noise)}"
)
print(
    "[render-env-candidate] "
    f"dropped_{current_label}_defaults={render(dropped_current_defaults)}"
)
print(
    "[render-env-candidate] "
    f"kept_{current_label}_review_extra={render(kept_current_review)}"
)
print(
    "[render-env-candidate] "
    f"diff_from_{reference_label}={render(diff_from_reference)}"
)
print(f"[render-env-candidate] output={output_file}")
PY
