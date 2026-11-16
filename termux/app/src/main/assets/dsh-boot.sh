#!/data/data/com.dshapp/files/usr/bin/bash
#
# DSH 一键启动脚本。
#
# 由 DshActivity 写入 $PREFIX/tmp 并执行，负责：
#   1. 可选：写入软件源镜像
#   2. 缺 Node/npm 时装上（apt）
#   3. 缺 dsh 时装上（npm -g）
#   4. 启动 dsh web，把输出（含带 token 的访问 URL）写进 dsh-web.log
#
# 进度写入 dsh-boot.status，App 轮询该文件显示状态；token URL 从 dsh-web.log 解析。
set -u

PREFIX=/data/data/com.dshapp/files/usr
TMP="$PREFIX/tmp"
STATUS="$TMP/dsh-boot.status"
LOG="$TMP/dsh-boot.log"
WEBLOG="$TMP/dsh-web.log"
MIRROR="${1:-}"

export PATH="$PREFIX/bin:$PATH"
export HOME="/data/data/com.dshapp/files/home"
export TMPDIR="$TMP"

mkdir -p "$TMP" 2>/dev/null

step() {
    printf '%s\n' "$1" > "$STATUS"
    printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$1" >> "$LOG"
}

step check

if [ -n "$MIRROR" ]; then
    step mirror
    mkdir -p "$PREFIX/etc/apt" 2>/dev/null
    printf 'deb %s stable main\n' "$MIRROR" > "$PREFIX/etc/apt/sources.list" 2>/dev/null
fi

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    step apt-update
    apt-get update -y >> "$LOG" 2>&1 || { step 'error:apt-update'; exit 1; }

    step install-node
    # --no-install-recommends：npm 是 nodejs-lts 的 Recommends，但我们显式装了，
    # 少拉一个包就少一次 .deb 改写。
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        nodejs-lts npm >> "$LOG" 2>&1 || { step 'error:install-node'; exit 1; }
fi

if ! command -v dsh >/dev/null 2>&1; then
    step install-dsh
    npm install -g @deepseek-ai/dsh >> "$LOG" 2>&1 || { step 'error:install-dsh'; exit 1; }
fi

if ! command -v dsh >/dev/null 2>&1; then
    step 'error:dsh-not-found'
    exit 1
fi

step start
: > "$WEBLOG"
dsh web >> "$WEBLOG" 2>&1
step stopped
