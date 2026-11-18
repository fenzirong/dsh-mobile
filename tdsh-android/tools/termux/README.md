# Termux 用户态（实验运行时）

设置里的「Termux 原生运行时（实验）」需要一份 bionic 用户态，放在：

```
files/probe/termux/usr/          ← 等价于 Termux 的 $PREFIX
  bin/node                       ← bionic 版 node
  bin/bash
  lib/node_modules/@deepseek-ai/dsh/lib/bin.js
files/probe/termux/home/         ← HOME（dsh 的 ~/.dsh 在这里）
files/probe/termux/root/         ← 空目录，只作为 tawcroot 的 -r
```

## 为什么不需要改前缀

Termux 的二进制把 `/data/data/com.termux/files/usr` 硬编码进了 RUNPATH/shebang，
但本 App 的包名（18 字节）与 `com.termux`（10 字节）不等长，**不能**做等长替换。
所以我们不改二进制，而是让 `Env.termuxCommand()` 用 tawcroot 把
`files/probe/termux/usr` **绑到** `/data/data/com.termux/files/usr`：
所有硬编码路径天然正确。详见 `docs/termux-route.md`。

## 手工准备（用于验证 phase 1）

在 Termux 里（或任何能产出 aarch64 bionic 二进制的环境）准备好文件后，
通过 `adb push` + `run-as` 放进上面的路径即可：

```bash
adb push usr.tar.gz /data/local/tmp/
adb shell run-as com.dsh.tdsh sh -c '
  mkdir -p files/probe/termux
  tar -xzf /data/local/tmp/usr.tar.gz -C files/probe/termux
'
```

`usr/` 里的最小集合（Termux 仓库的 aarch64 包解出来即可）：

| 包 | 用途 |
| --- | --- |
| `nodejs-lts` | node + npm |
| `bash` | dsh 的 bash 工具 |
| `libc++` `openssl` `c-ares` `libicu` `libsqlite` `zlib` | node 的依赖库 |

然后在 App 里打开开关，启动一次；`report.txt` 会写：
`✅ 运行方式：Termux 原生` 或 `⚠️ Termux 原生运行时不可用（GATE_FAIL_xxx）`。

## 自动化（下一步）

等手工路径在真机验过，再做「首次启动自动下载 + 解包 + 自检」，
流程与现在 rootfs/Node 的安装同构（断点续传 + 前台服务 + 阶段进度），
不需要任何前缀改写 —— 这是本方案相对"合并 Termux"实现省掉的最大一块。
