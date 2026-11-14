#!/usr/bin/env python3
"""重新生成内置 bootstrap。

把 Termux 官方 bootstrap 改造成 com.dshapp 版本：

1. 下载官方 bootstrap-aarch64.zip（或使用本地缓存）
2. 把所有条目内容里的 com.termux 等长替换为 com.dshapp
3. 注入 apt 钩子：bin/dshfix、bin/dsh-fixdeb、etc/apt/apt.conf.d/99dsh-prefix

用法：
    python3 tools/build-bootstrap.py [--ndk <NDK 路径>] [--keep]

需要：NDK（交叉编译 dshfix）、网络（首次下载 bootstrap）。
"""
import argparse
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

VERSION = "2026.02.12-r1%2Bapt.android-7"
URL = ("https://github.com/termux/termux-packages/releases/download/"
       "bootstrap-" + VERSION + "/bootstrap-aarch64.zip")

OLD = b"com.termux"
NEW = b"com.dshapp"
assert len(OLD) == len(NEW), "包名必须等长，否则会破坏 ELF 偏移"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
OUT = os.path.join(ROOT, "app/src/main/cpp/bootstrap-aarch64.zip")
CACHE = os.path.join(TOOLS, "bootstrap-upstream.zip")


def download():
    if os.path.exists(CACHE):
        print("使用缓存:", CACHE)
        return
    print("下载:", URL)
    urllib.request.urlretrieve(URL, CACHE)


def build_dshfix(ndk):
    src = os.path.join(TOOLS, "dshfix.c")
    dst = os.path.join(TOOLS, "dshfix-aarch64")
    if not ndk:
        if os.path.exists(dst):
            print("复用已有 dshfix-aarch64")
            return dst
        sys.exit("需要 --ndk 来交叉编译 dshfix")
    cc = os.path.join(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin/"
                           "aarch64-linux-android21-clang")
    strip = os.path.join(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
    subprocess.check_call([cc, "-static", "-O2", "-o", dst, src])
    subprocess.check_call([strip, "--strip-all", dst])
    print("编译完成:", dst, os.path.getsize(dst), "bytes")
    return dst


def patch_zip(src, dst, dshfix):
    extra = [
        ("bin/dshfix", dshfix, 0o755),
        ("bin/dsh-fixdeb", os.path.join(TOOLS, "dsh-fixdeb"), 0o755),
        ("etc/apt/apt.conf.d/99dsh-prefix", os.path.join(TOOLS, "99dsh-prefix"), 0o644),
    ]
    hits = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if OLD in data:
                data = data.replace(OLD, NEW)
                hits += 1
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = info.external_attr
            zout.writestr(zi, data)
        for arc, path, mode in extra:
            data = open(path, "rb").read()
            zi = zipfile.ZipInfo(arc, date_time=time.localtime()[:6])
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (0o100000 | mode) << 16
            zout.writestr(zi, data)
            print("注入:", arc, len(data), "bytes")
    print("改写条目:", hits)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME", ""))
    ap.add_argument("--keep", action="store_true", help="保留上游 zip 缓存")
    args = ap.parse_args()

    download()
    dshfix = build_dshfix(args.ndk)
    tmp = OUT + ".new"
    patch_zip(CACHE, tmp, dshfix)
    shutil.move(tmp, OUT)
    print("输出:", OUT, os.path.getsize(OUT), "bytes")
    if not args.keep:
        os.remove(CACHE)


if __name__ == "__main__":
    main()
