# Termux 路线（bionic 原生运行时）

> 目标：**绕开 glibc 容器**，用 Termux 那套 bionic 用户态直接跑 dsh。
> 这是理论上最快的一条：系统调用直达内核，没有 PRoot 的 ptrace，
> 也没有 tawcroot 的 seccomp 模拟。

## 1. 为什么考虑它

真机 V8 profile（59 秒冷启动）的结论：**86% 是路径类系统调用**，
因为 PRoot 对每一次 `stat/lstat/open/realpath` 都要 ptrace 停一次进程
（`voluntary_ctxt_switches: 140648`）。所以：

| 路线 | 启动时每次路径系统调用的代价 |
| --- | --- |
| PRoot（现状） | ptrace 往返：**~14 万次上下文切换** |
| tawcroot | 进程内 seccomp/SIGSYS 模拟（已接入，默认关） |
| **Termux 原生** | **直接进内核**（但见 §3 的前缀问题） |

## 2. 工作区里的参考实现说了什么

`dsh-mobile`（同作者的合并 Termux 版）走通了整条链路，值得抄的部分：

- **bootstrap**：官方 `bootstrap-aarch64.zip`（30.7 MB）编进 `libtermux-bootstrap.so`，
  首次启动离线解压；
- **apt 安装前改写 .deb**：`DPkg::Pre-Invoke` 钩子 → `dpkg-deb -R` 解包 →
  目录改名 + 内容字节替换 → `dpkg-deb -b -Zgzip -z1` 重打包；
- **重打包为什么用 gzip -1**：实测 48 MB 的 nodejs 包，`xz -6` 要 **45.8 s**，
  `gzip -1` 只要 **1.3 s**（35 倍），总体从约 8 分钟降到约 30 秒；
- **启停**：通过 Termux 的 app-shell runner 在后台跑脚本，脚本写阶段码，
  App 每 800 ms 轮询 + 解析 token URL；后端已在跑就**直接复用**；
- **镜像测速**：并行拉各镜像的 `dists/stable/Release`（十几 KB），建连+首字节+读完排名。

## 3. 我们不能照抄的那一步：等长前缀替换

参考实现的包名是 `com.dshapp`（**10 字节**），与 `com.termux`（10 字节）等长，
所以可以对 bootstrap 和每个 .deb 做**等长字节替换**而不破坏 ELF 偏移。

本 App 的包名是 `com.dsh.tdsh`（**18 字节**）。等长替换不成立：
Termux 的二进制把 `/data/data/com.termux/files/usr` 硬编码进了

- 脚本 shebang（文本，改多长都行）；
- ELF 的 `DT_RUNPATH`（`nodejs-lts` 有 37 个文件带此前缀）；
- bash 等程序的编译期默认路径（在 `.rodata` 里，改长会移动节偏移）。

## 4. 我们的接法：不动二进制，用 tawcroot 绑前缀

既然不能改字节，就**让那个路径存在**：

```
tawcroot -r <空根> \
  -b  files/probe/termux/usr : /data/data/com.termux/files/usr \
  -b  files/probe/termux/home : /root \
  -b  /system : /system   -b /dev -b /proc -b /sys -b /sdcard … \
  -- /bin/sh -c 'cd /sdcard/dsh && exec $PREFIX/bin/node …/dsh/lib/bin.js --profile web …'
```

好处：

1. **一个字节都不补丁** —— shebang、RUNPATH、编译期路径全部天然正确；
2. 顺带解决**硬链接**：原生模式下 SELinux 拒绝 `link(2)`（dsh 的原子写、
   pnpm 都依赖它），tawcroot 自带硬链接模拟；
3. 没有 Ubuntu、没有 glibc loader，rootfs 是个空目录（只有一条前缀绑定）。

代价：仍然有 tawcroot 的 seccomp 开销（但只在**命中前缀绑定**的路径上，
且是进程内处理，比 ptrace 便宜一个量级）；单进程 → 没有隔离（与 tawcroot 同）。

## 5. 代码里现在到哪一步

| 组件 | 状态 |
| --- | --- |
| `Env.RT_TERMUX` / `termuxCommand()` | ✅ 已实现（tawcroot + 前缀绑定 + bionic node） |
| `Env.termuxInstalled()` | ✅ 只检查 `termux/usr/bin/node` 与 `…/dsh/lib/bin.js` |
| `TermuxGate` | ✅ 探针：node/bash/dsh 存在 + 硬链接/符号链接/exec//proc//sdcard/pnpm |
| 设置项「Termux 原生运行时（实验）」 | ✅ 默认关；没装用户态就安静回落 PRoot |
| **用户态安装器** | ⬜ **未做** —— 见 §6 |

## 6. 还差什么（下一步）

1. **用户态落盘**：把 Termux 的 `bootstrap-aarch64.zip`（或按需的最小集合
   `nodejs-lts + bash + libc++ + openssl + c-ares + libicu + libsqlite + zlib`）
   解到 `files/probe/termux/usr`。因为是**绑定到 Termux 的路径**，
   **不需要**任何前缀改写或重打包 —— 这是本方案相对参考实现省掉的最大一块；
2. **在原生环境里装 dsh**：`$PREFIX/bin/npm i -g @deepseek-ai/dsh`
   （网络一次；之后插件市场用原生 npm/pnpm 即可）；
3. **验证**：打开设置里的开关 → 看 `report.txt` 是
   `✅ 运行方式：Termux 原生` 还是 `⚠️ …不可用（原因）`；
4. **插件市场回归**：装一个纯 JS 插件，确认能装能生效（闸门已覆盖底层能力）。

> 未验证的部分我不随包发：Termux 用户态是 ~30 MB 的第三方二进制，
> 而这里没有 arm64 真机可跑。等 §6.1–6.3 在你的机器上验过一次，
> 再决定是否把它做成"首次启动自动下载并安装"（和现在 rootfs 的安装流程同构）。
