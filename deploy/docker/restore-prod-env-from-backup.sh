#!/usr/bin/env bash
set -euo pipefail

BACKUP_TAR="${1:?用法: restore-prod-env-from-backup.sh <systemd_cleanup_backup.tar.gz> <target_env_file>}"
TARGET_ENV="${2:?用法: restore-prod-env-from-backup.sh <systemd_cleanup_backup.tar.gz> <target_env_file>}"
SOURCE_MEMBER="etc/status-mvp-price-backend/status-mvp-price-backend.env"

log() {
  printf '[restore-prod-env] %s\n' "$*"
}

fail() {
  printf '[restore-prod-env] %s\n' "$*" >&2
  exit 1
}

[[ -f "$BACKUP_TAR" ]] || fail "未找到备份包: $BACKUP_TAR"

mkdir -p "$(dirname "$TARGET_ENV")"
tar -xzf "$BACKUP_TAR" -O "$SOURCE_MEMBER" >"$TARGET_ENV"
chmod 600 "$TARGET_ENV"

log "已恢复生产 ENV 文件: $TARGET_ENV"
log "来源备份包: $BACKUP_TAR"
