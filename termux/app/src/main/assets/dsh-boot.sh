#!/data/data/com.dshapp/files/usr/bin/bash
#
# DSH 一键启动脚本。
#
# 由 DshActivity 写入 $PREFIX/tmp 并执行，负责：
#   1. 可选：写入软件源镜像
#   2. 缺 Node/npm 时装上（apt）
#   3. 缺 dsh 时装上（npm -g）
#   4. 首次运行时先让 profile 落地，再把随 APK 自带的 DSH 插件装进去
#   5. 启动 dsh web，把输出（含带 token 的访问 URL）写进 dsh-web.log
#
# 进度写入 dsh-boot.status，App 轮询该文件显示状态；token URL 从 dsh-web.log 解析。
set -u

PREFIX=/data/data/com.dshapp/files/usr
TMP="$PREFIX/tmp"
STATUS="$TMP/dsh-boot.status"
LOG="$TMP/dsh-boot.log"
WEBLOG="$TMP/dsh-web.log"
PLUGIN_DIR="$TMP/dsh-plugins"
MIRROR="${1:-}"

export PATH="$PREFIX/bin:$PATH"
export HOME="/data/data/com.dshapp/files/home"
export TMPDIR="$TMP"

PROFILE="$HOME/.dsh/profiles/web"
mkdir -p "$TMP" 2>/dev/null

step() {
    printf '%s\n' "$1" > "$STATUS"
    printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$1" >> "$LOG"
}

# 把随 APK 自带的插件装进 profile。
#
# 不走 `dsh plugin add`（它内部转发 pnpm，运行时里没有 pnpm，还要联网）；
# 我们的插件零依赖，直接解包到 node_modules 再把包名写进 package.json 的
# dsh.profile.bundles 即可 —— 这正是 pnpm 装完该有的样子。
install_plugins() {
    [ -f "$PROFILE/package.json" ] || { echo "profile 未就绪，跳过插件安装"; return 1; }
    [ -d "$PLUGIN_DIR" ] || { echo "没有自带插件"; return 1; }

    local patcher="$TMP/dsh-plugin-patch.cjs"
    cat > "$patcher" <<'JSEOF'
const fs = require('fs');
const [pkgPath, name, tgz] = process.argv.slice(2);
const o = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
o.dependencies = o.dependencies || {};
o.dependencies[name] = 'file:./' + tgz;
o.dsh = o.dsh || {};
o.dsh.profile = o.dsh.profile || {};
const b = o.dsh.profile.bundles = o.dsh.profile.bundles || [];
if (!b.includes(name)) b.push(name);
fs.writeFileSync(pkgPath, JSON.stringify(o, null, 2));
JSEOF

    local changed=0
    local tgz base name tmp
    for tgz in "$PLUGIN_DIR"/*.tgz; do
        [ -e "$tgz" ] || continue
        base="${tgz##*/}"
        name="${base%.tgz}"
        [ -d "$PROFILE/node_modules/$name" ] && continue

        tmp="$TMP/dshp.$$"
        rm -rf "$tmp"
        mkdir -p "$tmp" || continue
        if tar -xzf "$tgz" -C "$tmp" 2>/dev/null && [ -d "$tmp/package" ]; then
            mkdir -p "$PROFILE/node_modules"
            rm -rf "$PROFILE/node_modules/$name"
            mv "$tmp/package" "$PROFILE/node_modules/$name"
            cp -f "$tgz" "$PROFILE/$base" 2>/dev/null
            node "$patcher" "$PROFILE/package.json" "$name" "$base" >> "$LOG" 2>&1 && changed=1
        fi
        rm -rf "$tmp"
    done

    [ "$changed" = "1" ] && { echo "已安装自带插件"; return 0; }
    echo "自带插件已是最新"
    return 1
}

# apt 的超时与重试：默认没有超时，遇到半死连接会无限等待，
# 表现就是界面一直停在「正在安装 Node.js 与 npm」。
APT_OPTS="-o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Acquire::Retries=3"

step check

if [ -n "$MIRROR" ]; then
    step mirror
    mkdir -p "$PREFIX/etc/apt" 2>/dev/null
    printf 'deb %s stable main\n' "$MIRROR" > "$PREFIX/etc/apt/sources.list" 2>/dev/null
fi

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    step apt-update
    apt-get $APT_OPTS update >> "$LOG" 2>&1 < /dev/null || { step 'error:apt-update'; exit 1; }

    step install-node
    # --no-install-recommends：npm 是 nodejs-lts 的 Recommends，但我们显式装了，
    # 少拉一个包就少一次 .deb 改写。
    # 配置文件冲突一律用本地版本，避免任何交互式提问。
    DEBIAN_FRONTEND=noninteractive apt-get $APT_OPTS install -y --no-install-recommends \
        -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
        nodejs-lts npm >> "$LOG" 2>&1 < /dev/null || { step 'error:install-node'; exit 1; }
fi

if ! command -v dsh >/dev/null 2>&1; then
    step install-dsh
    npm install -g @deepseek-ai/dsh >> "$LOG" 2>&1 < /dev/null || { step 'error:install-dsh'; exit 1; }
fi

if ! command -v dsh >/dev/null 2>&1; then
    step 'error:dsh-not-found'
    exit 1
fi

# profile 是插件与配置的落点，首次运行需要先让它生成出来。
# 做法：短暂起一次 dsh web，等 package.json 出现就杀掉，随后正式启动。
if [ ! -f "$PROFILE/package.json" ]; then
    step init-profile
    dsh web > "$TMP/dsh-profile-init.log" 2>&1 &
    init_pid=$!
    waited=0
    while [ "$waited" -lt 120 ]; do
        [ -f "$PROFILE/package.json" ] && break
        sleep 1
        waited=$((waited + 1))
    done
    kill "$init_pid" 2>/dev/null
    wait "$init_pid" 2>/dev/null
    sleep 1
    [ -f "$PROFILE/package.json" ] || echo "profile 初始化超时，本次跳过插件安装" >> "$LOG"
fi

step install-plugins
install_plugins >> "$LOG" 2>&1 || true

step start
: > "$WEBLOG"
dsh web >> "$WEBLOG" 2>&1
step stopped
