#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

log() {
  printf '[package-release-source] %s\n' "$*"
}

fail() {
  printf '[package-release-source] %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1"
}

require_command git
require_command tar

cd "$REPO_ROOT"

if [[ -z "${ALLOW_DIRTY:-}" ]] && [[ -n "$(git status --porcelain)" ]]; then
  fail "当前仓库存在未提交改动。正式发布源码快照必须来自干净工作树；如确需跳过，请显式设置 ALLOW_DIRTY=1"
fi

GIT_SHA="$(git rev-parse --short HEAD)"
OUTPUT_PATH="${1:-$REPO_ROOT/dist/status-mvp-price-backend-${GIT_SHA}-${TIMESTAMP}.tar.gz}"
mkdir -p "$(dirname "$OUTPUT_PATH")"

git archive --format=tar HEAD | gzip -n >"$OUTPUT_PATH"

log "源码快照已生成: $OUTPUT_PATH"
log "发布来源提交: $GIT_SHA"
