# tawcroot —— 加速启动（PRoot 替代）

## 它解决什么问题

真机 V8 profile（59 秒冷启动）的结论是：**86% 的时间花在路径类系统调用上**，
因为 PRoot 用 `ptrace` 拦截每一次 `stat/lstat/open/realpath` 去做路径翻译，
每次都要让进程停一下。实测 `voluntary_ctxt_switches: 140648`。

`tawcroot`（来自 [wmww/tawc](https://github.com/wmww/tawc)）用 **seccomp `RET_TRAP` + 进程内
`SIGSYS` 处理**（"systrap"）代替 ptrace：路径翻译、fake root、硬链接模拟全在
**同一个进程**里完成，没有 tracer、没有每次系统调用的上下文切换。
上游实测启动约快 3 倍。

## 与 PRoot 的差异（都已在代码里处理）

| 项 | PRoot | tawcroot |
| --- | --- | --- |
| 调用形式 | `proot -r … -0 -w … --link2symlink --kill-on-exit -b …` | `tawcroot [-s] -r ROOTFS [-b SRC:DST[:ro]]… -- CMD` |
| 假 root | `-0` | 总是伪装 uid 0，无开关 |
| 硬链接 | `--link2symlink`（用符号链接冒充） | **原生模拟**，仓库在 `<rootfs>/../tawcroot` |
| 工作目录 | `-w` | 没有该参数 → 用 `sh -c 'cd … && exec …'` |
| 只读绑定 | 不支持 | `-b SRC:DST:ro`（token 目录已改用它） |
| 进程模型 | tracer + tracee | **单进程**（+ `PDEATHSIG`） |

因为它是单进程，**清理逻辑必须认得它**：`Env.killStaleDsh` 与 `ProcLogic.classify`
都加了 tawcroot 判据；清空容器时也会把它那个硬链接仓库 `<base>/tawcroot` 一起删掉
（只删 rootfs 会留下一堆指向不存在文件的 token）。

## 准入闸门（为什么它不会弄坏插件市场）

设置里打开后，**首次启动会先在容器里跑一遍自检**，任一项不过就自动回落 PRoot，
并把原因写进设置页（`TawcrootGate`）：

1. **硬链接**：`ln` 后 `cat` 能读、`st_nlink ≥ 2`（dsh 的原子写、pnpm 的布局都靠它）；
2. **符号链接**：`ln -s` + `readlink`（pnpm 的 `node_modules` 就是链接树）;
3. **execve**：`node -e 0`、`sh -c`（起 node / bash 工具 / npm）;
4. **/proc**：`/proc/self/status` 可读（node 与 App 心跳都读）；
5. **/sdcard 绑定**：可写可读（工作区 + 手机文件插件）;
6. **pnpm**：已装则必须能跑（插件市场前置条件）。

自检结论按 **versionCode** 缓存：App 升级后自动重测一次。

## 许可（重要）

tawcroot 是 **GPL-3.0**，与上游 TAWC 项目一致。我们随 APK 分发它的二进制
（`app/src/main/assets/tawcroot`，静态链接 aarch64，**未修改**），因此
**分发 APK 时需同时让接收者能获得对应源码**：

- 对应源码：<https://github.com/wmww/tawc> 的 **v6** tag；
- 许可全文：`LICENSES/GPL-3.0.txt`；
- 我们的说明：`THIRD-PARTY.md` 的「4b. tawcroot」；
- 不想承担该义务：删掉 `app/src/main/assets/tawcroot` 即可，功能自动退回 PRoot。

## 更新二进制

```bash
./tools/tawcroot/fetch.sh            # 下载 v6 APK 并取出 libtawcroot.so + 校验
TAWC_VERSION=v7 ./tools/tawcroot/fetch.sh   # 换版本（记得同步 THIRD-PARTY.md）
```
