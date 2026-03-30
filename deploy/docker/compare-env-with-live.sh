#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:?用法: compare-env-with-live.sh <env_file> [container_name]}"
CONTAINER_NAME="${2:-status-mvp-price-backend}"

python3 - "$ENV_FILE" "$CONTAINER_NAME" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

env_file = Path(sys.argv[1])
container_name = sys.argv[2]

if not env_file.exists():
    raise SystemExit(f"[compare-env] 未找到 ENV 文件: {env_file}")

def parse(lines):
    result = {}
    for line in lines:
        raw = line.strip()
        if not raw or raw.startswith("#") or "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        result[key] = value
    return result

env_payload = parse(env_file.read_text().splitlines())
inspect = json.loads(subprocess.check_output(["docker", "inspect", container_name], text=True))[0]
live_payload = parse(inspect["Config"]["Env"])

for runtime_key in ["PATH", "JAVA_HOME", "JAVA_VERSION", "LANG", "LANGUAGE", "LC_ALL"]:
    live_payload.pop(runtime_key, None)

missing_in_file = sorted([key for key in live_payload if key not in env_payload])
missing_in_live = sorted([key for key in env_payload if key not in live_payload])
value_mismatch = sorted([key for key in env_payload if key in live_payload and env_payload[key] != live_payload[key]])

print(f"[compare-env] env_file_keys={len(env_payload)} live_keys={len(live_payload)}")
print(f"[compare-env] missing_in_file={','.join(missing_in_file) or '-'}")
print(f"[compare-env] missing_in_live={','.join(missing_in_live) or '-'}")
print(f"[compare-env] value_mismatch={','.join(value_mismatch) or '-'}")

if missing_in_file or missing_in_live or value_mismatch:
    raise SystemExit(1)
PY
