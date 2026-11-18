#!/usr/bin/env bash
# 取出 tawcroot 二进制（放进 app/src/main/assets/tawcroot）。
#
# 为什么用"从 TAWC 的 release APK 里取"而不是自己编译：
#   1. 上游把它打包成 libtawcroot.so 塞进 jniLibs —— Android 只允许从
#      nativeLibraryDir / 应用私有目录执行，这个打包方式正好是我们需要的形态；
#   2. 自己编译要 NDK + 上游 vendored 的 proot 参考实现，代价远大于收益；
#   3. 我们不修改它一个字节，GPLv3 的"对应源码"就是上游 tag v6 本身（见 THIRD-PARTY.md）。
#
# 用法：./tools/tawcroot/fetch.sh            # 下载 v6 并校验
set -euo pipefail

VER="${TAWC_VERSION:-v6}"
APK="tawc-${VER}.apk"
URL="https://github.com/wmww/tawc/releases/download/${VER}/${APK}"
DEST="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/assets/tawcroot"

# 我们随包分发的那个二进制的 SHA-256（换版本时同步更新 THIRD-PARTY.md）
EXPECT="48eeb93c0083f33f6e53b722684e704ba9b2a34d90f681c560e06edfa871f323"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== 下载 $URL"
curl -fsSL "$URL" -o "$TMP/$APK"

echo "== 从 APK 取出 lib/arm64-v8a/libtawcroot.so"
unzip -o -q "$TMP/$APK" "lib/arm64-v8a/libtawcroot.so" -d "$TMP"
cp "$TMP/lib/arm64-v8a/libtawcroot.so" "$DEST"
chmod 644 "$DEST"

GOT="$(sha256sum "$DEST" | cut -d' ' -f1)"
echo "== SHA-256: $GOT"
if [ "$VER" = "v6" ] && [ "$GOT" != "$EXPECT" ]; then
  echo "!! 校验不一致（期望 $EXPECT）" >&2
  echo "!! 上游可能重新发布了 v6；确认无误后同步更新本脚本与 THIRD-PARTY.md" >&2
  exit 1
fi
echo "== OK: $DEST"
