#!/usr/bin/env bash
# 改包名（applicationId / namespace / Java 包 / AIDL / 文档里的硬编码）。
#
# 为什么要有脚本：包名散落在 100+ 处（Java package/import、AIDL 的 package 声明与目录、
# AndroidManifest 的组件名、build.gradle 的 namespace/applicationId、AGENTS.md 里给
# agent 看的 `pm grant` 命令、docs 里的 `run-as` 示例…），手改必漏。
#
# 用法：./tools/rename-package.sh com.dshmobile.probe com.dsh.tdsh
#
# ⚠️ 改包名 = 换一个 App 身份：新包名装出来是**另一个应用**，
#    旧包的容器（约 150MB）、API Key、白名单都不会跟过来，两者可以并存。
set -euo pipefail

OLD="${1:?用法: rename-package.sh <旧包名> <新包名>}"
NEW="${2:?用法: rename-package.sh <旧包名> <新包名>}"
OLD_PATH="${OLD//./\/}"
NEW_PATH="${NEW//./\/}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "== $OLD  →  $NEW"

# 1) 目录：Java 源码与 AIDL 必须跟着包名走（AIDL 的 package 声明与路径要一致）
for base in app/src/main/java app/src/main/aidl; do
  if [ -d "$base/$OLD_PATH" ]; then
    mkdir -p "$base/$(dirname "$NEW_PATH")"
    git mv "$base/$OLD_PATH" "$base/$NEW_PATH" 2>/dev/null || mv "$base/$OLD_PATH" "$base/$NEW_PATH"
    echo "   移动 $base/$OLD_PATH → $base/$NEW_PATH"
  fi
done

# 2) 内容：只改 git 跟踪的文本文件（不碰 build/ 产物）
count=0
# 用 -z + read -d '' 遍历：文件名里可能有空格/中文，按空白切分会漏（第一次跑就漏了两个文档）
while IFS= read -r -d '' f; do
  case "$f" in
    *.png|*.jpg|*.jpeg|*.webp|*.gif|*.so|*.tgz|*.zip|*.apk|*.jar|*.keystore|*.ttf|*.otf) continue ;;
  esac
  [ -f "$f" ] || continue
  if grep -qF "$OLD" "$f" 2>/dev/null || grep -qF "$OLD_PATH" "$f" 2>/dev/null; then
    # 先替换"斜杠形式"（路径），再替换点号形式，避免互相污染
    sed -i "s|$OLD_PATH|$NEW_PATH|g; s|$OLD|$NEW|g" "$f"
    count=$((count + 1))
  fi
done < <(git ls-files -z)
echo "   改写文件数：$count"

# 3) 自检：不该再有旧包名残留
left="$(grep -rn "$OLD" --include='*.java' --include='*.xml' --include='*.gradle' \
        --include='*.aidl' --include='*.md' --include='*.sh' --include='*.yml' . 2>/dev/null \
        | grep -v '^./DSH-Mobile-Android-源码' | grep -v 'rename-package.sh' || true)"
if [ -n "$left" ]; then
  echo "!! 仍有残留：" >&2
  echo "$left" | head -20 >&2
  exit 1
fi
echo "== 完成。记得："
echo "   · 新包名是**另一个应用**，旧包的容器/Key/白名单不会迁移；"
echo "   · 文档里的 adb 命令（pm grant / run-as）已同步改写；"
echo "   · 发布前确认签名密钥不变（同 key 才能覆盖安装同包名的后续版本）。"
