#!/usr/bin/env bash
# 构建「内置 Termux」合并版 APK。
#
# 前置条件：
#   - JDK 17
#   - Android SDK：platform 34、build-tools 34.0.0
#   - NDK 29.0.14206865（Termux 的 JNI 与 bootstrap 打包需要）
#   - 建议至少 4 GB 可用内存（本工程 4 个模块 + NDK 编译，内存不足会被 OOM 杀掉）
#
# 用法：
#   ./build.sh            # 构建 debug APK
#   ./build.sh release    # 构建 release APK（需自行配置签名）
set -euo pipefail

cd "$(dirname "$0")"

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
  echo "请先设置 ANDROID_HOME 指向 Android SDK" >&2
  exit 1
fi
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ ! -f local.properties ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi

if [ ! -f app/src/main/cpp/bootstrap-aarch64.zip ]; then
  echo "缺少 app/src/main/cpp/bootstrap-aarch64.zip（已改写 com.dshapp 前缀的 bootstrap）" >&2
  echo "可从 termux-packages releases 下载后按 README 的说明改写前缀。" >&2
  exit 1
fi

TASK="assembleDebug"
if [ "${1:-}" = "release" ]; then
  TASK="assembleRelease"
fi

./gradlew --no-daemon "$TASK"

echo
echo "产物："
find app/build/outputs/apk -name '*.apk' -print
