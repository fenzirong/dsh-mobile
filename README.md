# DSH Mobile

把 DeepSeek Harness（DSH）搬到安卓上的三个产物，均由 GitHub Actions 构建并发布：

**下载地址：https://github.com/fenzirong/dsh-mobile/releases/latest**

| 产物 | 包名 | 体积 | 说明 |
| --- | --- | --- | --- |
| `DSH-Mobile-shell.apk` | `com.dsh.mobile` | 2.8 MB | 纯前端外壳，连接 Termux 里的 DSH 主机 |
| `DSH-Mobile-merged-arm64.apk` | `com.dshapp` | 36.8 MB | 内置 Termux 的合并版，一个 APK 同时含终端与前端（仅 arm64-v8a） |
| `Tdsh-<版本>.apk`（`tdsh-android/`） | `com.dsh.tdsh` | 约 4.7 MB | **PRoot + Ubuntu + Node + DSH** 的自包含版：不依赖 Termux，内置手机控制（无障碍/Shizuku）、插件市场、容器接管等；源码在 [`tdsh-android/`](tdsh-android/)，发版 tag 用 `tdsh-v*` |

三者共用同一套前端逻辑：**DSH 的 Web GUI 原样跑在 WebView 里**，所以所有已安装的
客户端插件（侧边栏、终端、文件、插件市场、日程、子代理……）都自动生效，不需要为安卓重写。

---

## 零、`tdsh-android/`：自包含的 PRoot 版

本仓库除了上面两个「外壳 / 合并 Termux」产物，还收了一个**自包含**的安卓版
（原 `Tdsh-DSH-Mobile-Android-NO-ROOT`，已迁入本仓库）：

| 项 | 说明 |
| --- | --- |
| 包名 | `com.dsh.tdsh`（与上面两个产物**互不冲突**，可同时安装） |
| 运行方式 | 内置 PRoot + Ubuntu 24.04 + Node + `dsh web`；不需要 Termux、不需要 root |
| 手机控制 | 无障碍 / Shizuku / 命令后端 + 应用白名单；`phone` CLI 走本机回环控制桥 |
| 插件 | 官方 Web UI 原样跑在 WebView 里 → **客户端插件 100% 兼容**；插件市场可装第三方插件 |
| 构建 | `.github/workflows/tdsh-android-build.yml`（push 即出 debug APK） |
| 发版 | `.github/workflows/tdsh-android-release.yml`（打 `tdsh-v*` tag，签名 + 发布） |
| 文档 | [`tdsh-android/README.md`](tdsh-android/README.md)、[`tdsh-android/CHANGELOG.md`](tdsh-android/CHANGELOG.md) |

> 它和「合并 Termux」版是两条不同的技术路线：合并版用 **bionic 原生 node**（启动快，
> 但需要前缀改写）；`tdsh-android` 用 **PRoot 容器**（兼容性最好，启动慢一些），
> 并额外接了 tawcroot 与 Termux 原生两条可选运行时（默认关，先自检再启用）。

---

## 一、为什么是「外壳 + 主机」而不是原生重写

DSH 本体是 Node.js 服务，插件体系（Cordis）分宿主插件与客户端插件两层：

- **宿主插件**跑在 Node 进程里，提供工具、模型、存储等能力；
- **客户端插件**是打进 Web GUI 的前端 bundle，负责界面。

安卓端要「支持各种插件」，唯一不丢功能的做法就是让真正的 DSH 主机跑起来，前端只做呈现。
原生重写只能覆盖极小子集，且永远追不上插件生态。

---

## 二、鉴权机制（外壳能连上的关键）

`dsh web` 启动后会打印形如：

```
http://127.0.0.1:3090/?token=<launchToken>
```

访问该 URL 时，服务端校验 `token` 查询参数，种下绑定 `host:port` 的签名 Cookie，
然后 303 跳转到干净的 `./`。之后所有请求靠 Cookie 鉴权。

因此外壳的做法是：

1. 用户粘贴这条完整 URL（或只粘贴 token）；
2. WebView 加载它 → 自动换 Cookie → 落到正常界面；
3. Cookie 由 `CookieManager` 持久化，下次启动直接可用；
4. 令牌随 `dsh web` 进程变化，重启后旧 Cookie 失效 → 服务端返回 401 →
   外壳捕获主框架 401，弹出重新授权面板。

---

## 三、外壳版使用步骤

1. 安装 Termux（F-Droid 版）。
2. 在 Termux 中：

   ```bash
   pkg update
   pkg install nodejs-lts
   npm i -g @deepseek-ai/dsh
   dsh web
   ```

3. 复制它打印的 `http://127.0.0.1:3090/?token=...`。
4. 打开 DSH Mobile → 「粘贴」→「连接」。

外壳还支持：

- **启动后端**：通过 Termux 的 `RUN_COMMAND` 服务在后台拉起 `dsh web`
  （需在 `~/.termux/termux.properties` 设 `allow-external-apps=true`）；
- **桌面模式**：按 980px 视口渲染再整体缩放，得到与桌面一致的布局，可双指缩放；
- 文件上传（附件）、下载（交付物）、外链跳转、摄像头/麦克风授权（语音输入插件）。

---

## 四、合并版（内置 Termux）

`termux/` 是 termux-app 源码的改造版，改动点：

1. **包名** `com.termux` → `com.dshapp`；
2. **bootstrap 前缀改写**：Termux 的二进制里把 `/data/data/com.termux/files/usr`
   写死了，所以必须同步改写。这里选了与 `com.termux` **等长（10 字符）** 的
   `com.dshapp`，对 bootstrap 做等长字节替换，避免破坏 ELF 内部偏移；
3. **内置 bootstrap**：改写后的 `bootstrap-aarch64.zip` 放进 `app/src/main/cpp/`，
   构建时直接编进 `libtermux-bootstrap.so`，首次启动离线解压，无需联网；
4. **新增 `DshActivity`** 作为唯一启动入口（前端），`TermuxActivity` 保留为终端；
5. **软件源镜像**：菜单里可一键切换官方 / 清华 / 中科大 / 北外 / 南大 / 哈工大 / 山大，
   直接改写 `$PREFIX/etc/apt/sources.list`；
6. **一键启动**：见下节。

### 一键启动

首次打开只需点一次「一键启动」，App 会自动完成整条链路：

```
检查环境 → 并行测速镜像源（选最快） → 写入 sources.list → apt update
        → 装 nodejs-lts + npm → npm i -g @deepseek-ai/dsh
        → 启动 dsh web → 抓取 token URL → 加载界面
```

**镜像测速**：并行请求每个镜像的 `dists/stable/Release`（apt 仓库里十几 KB 的元数据），
以「建连 + 首字节 + 读完」的耗时排名，最短者胜出。4 s 超时、8 s 整体兜底，
全部失败则回退到用户上次选择的源。测速结果会写回偏好，界面上显示「最快镜像：清华 TUNA（320 ms）」。

实现方式：

- `assets/dsh-boot.sh` 由 App 释放到 `$PREFIX/tmp/dsh-boot.sh`；
- 通过 `TermuxService` 的 **app-shell** runner 在后台执行（不占终端会话，
  App 退到后台也不会被回收）；
- 脚本把阶段码写进 `dsh-boot.status`，把 `dsh web` 的输出写进 `dsh-web.log`；
- `DshLauncher` 每 800 ms 轮询这两个文件，解析出 `?token=...` 的地址后直接加载；
- 若后端已在运行（端口在监听且日志里有 token），打开 App 会**自动复用**，连按钮都不用点。

界面上有进度条与百分比。由于 `apt install` / `npm install` 的耗时不可预知，
进度不是按时间线性推进，而是「阶段权重 + 阶段内渐进逼近上限」：

| 阶段 | 进度区间 |
| --- | --- |
| check | 0 → 5% |
| bench（测速） | 5 → 12% |
| mirror | 12 → 15% |
| apt-update | 15 → 32% |
| install-node | 32 → 62% |
| install-dsh | 62 → 86% |
| start | 86 → 95% |
| 完成 | 100% |

阶段内按 `1 - e^(-t/τ)` 逼近本段上限（永远差一格），进入下一阶段时跳到下一段起点，
既不会假装知道剩余时间，也不会长时间停在一个数字上。首次解压 bootstrap 时显示不确定进度。

失败时状态栏会显示具体阶段（如 `error:install-node`），可点「打开终端」查看
`$PREFIX/tmp/dsh-boot.log`，或切换软件源镜像后重试。

### 构建

推荐用 GitHub Actions（内存充足，NDK 编译稳定）：

```bash
git tag v1.0.4 && git push origin v1.0.4   # 触发构建并自动发布 Release
```

工作流：`.github/workflows/release.yml`，使用 runner 预装的 Android SDK，
安装 `ndk;29.0.14206865` / `platforms;android-36` / `build-tools;35.0.0` 后分别构建两个 APK，
打 tag 时用 `softprops/action-gh-release` 发布。

**内置 bootstrap 不入库**（29 MB 的二进制，每次改动都会让仓库膨胀），
构建前由 `tools/build-bootstrap.py` 现生成：下载上游 → 等长改写前缀 → 注入 apt 钩子。
CI 里这一步在「Build merged APK」之前自动执行。

本地构建（需 ≥ 4 GB 可用内存 + 充足磁盘 I/O）：

```bash
cd termux
# 需要：JDK 17、SDK platform 36、build-tools 35.0.0、NDK 29.0.14206865
./build.sh          # 缺 bootstrap 时会自动生成，然后 assembleDebug
```

产物：`termux/app/build/outputs/apk/debug/`（仅 arm64-v8a）。

> 注：本仓库的开发环境磁盘仅 4.7 MB/s、内存 887 MB，本地构建合并版会因 I/O 与内存被拖死，
> 因此合并版的构建与发布全部放在 CI 完成。

### 与官方 Termux 共存：前缀改写钩子

合并版包名是 `com.dshapp`，数据目录 `/data/data/com.dshapp`，与官方 Termux 的
`/data/data/com.termux` 完全隔离，两者可以同时安装。

但 Termux 官方仓库的软件包把 `/data/data/com.termux/files/usr` **硬编码**进了
脚本 shebang、ELF 的 `RUNPATH` 和默认路径。例如 `nodejs-lts` 包里有 37 个文件带这个前缀，
`node` 的 `RUNPATH` 直接指向 `/data/data/com.termux/files/usr/lib`——直接装进
`com.dshapp` 会得到一个起不来的 node。

解决办法是在 **dpkg 安装前**改写包内容：

```
apt 下载 .deb → DPkg::Pre-Invoke 触发 dsh-fixdeb
  → dpkg-deb -R 解包
  → 把包内路径 data/data/com.termux 改名为 data/data/com.dshapp
  → dshfix 把文件内容里的 com.termux 等长替换为 com.dshapp（二进制安全）
  → dpkg-deb -b -Zgzip -z1 --root-owner-group 重新打包
  → dpkg 安装已适配的包
```

**为什么用 gzip -1 而不是 xz**：实测重打包 nodejs 包（48 MB 数据），
`xz -6` 要 **45.8 s**，`gzip -1` 只要 **1.3 s**，快 35 倍。这个 .deb 只是给 dpkg
立刻消费的临时产物，体积从 9.6 MB 变成 17 MB 完全无所谓。首次安装十来个包，
重打包总耗时从约 8 分钟降到约 30 秒。没有实际改动（既没改名也没改写任何文件）的包
还会直接跳过重打包。

必须在安装前做：dpkg 会直接 `execve` 执行 `preinst`/`postinst`，若 shebang 仍指向
`/data/data/com.termux/...` 就会 `ENOENT`，安装直接失败。

之所以能等长替换：`com.termux` 与 `com.dshapp` 都是 10 字节，替换不会破坏 ELF 内部偏移。

相关文件：

| 文件 | 作用 |
| --- | --- |
| `termux/tools/dshfix.c` | 二进制安全的等长替换工具（静态编译进 bootstrap） |
| `termux/tools/dsh-fixdeb` | apt 的 `DPkg::Pre-Invoke` 钩子脚本 |
| `termux/tools/99dsh-prefix` | 注册钩子的 apt 配置 |
| `termux/tools/build-bootstrap.py` | 从官方 bootstrap 重新生成内置 bootstrap |

钩子日志：`$PREFIX/tmp/dsh-fixdeb.log`。

> 局限：被压缩的文件（如 `.gz` man page、`.pyc`）里的路径不会被改写，属于非关键内容；
> 若某个包解包或重打包失败，钩子会跳过它并记日志，该包可能无法运行。

---

## 五、目录结构

```
dsh-mobile/
├── .github/workflows/       # CI：release.yml（v* 发版）、tdsh-android-*.yml
├── android/                 # 外壳版（com.dsh.mobile）
│   ├── app/src/main/java/com/dsh/mobile/
│   │   ├── MainActivity.java    # WebView 外壳、鉴权、401 重授权、下载/上传
│   │   ├── Prefs.java           # 连接配置持久化
│   │   └── TermuxBridge.java    # RUN_COMMAND 拉起后端
│   └── keystore/debug.keystore  # 调试签名（公开测试密钥）
├── termux/                  # 合并版（com.dshapp，内置 Termux）
│   ├── app/src/main/java/com/termux/app/DshActivity.java
│   ├── app/src/main/java/com/dsh/mobile/        # DshPrefs/DshMirrors/DshBackend/
│   │                                            # DshLauncher/DshProgress/MirrorBenchmark
│   ├── app/src/main/assets/dsh-boot.sh          # 一键启动脚本
│   ├── app/src/main/cpp/bootstrap-aarch64.zip   # 构建时生成，不入库
│   └── tools/                                   # dshfix.c / dsh-fixdeb / build-bootstrap.py
├── tdsh-android/            # PRoot 自包含版（com.dsh.tdsh）
└── dist/                    # 本地构建产物（已 gitignore，不入库）
```

> 工作流只认根目录 `.github/workflows/`；`tdsh-android/` 里不再保留嵌套的 workflow 副本。

---

## 六、已知限制

- 合并版只打 `arm64-v8a`（现代手机通用），如需 32 位需另备对应 bootstrap；
- 令牌与 `dsh web` 进程同生命周期，重启后端需重新粘贴 URL；
- 桌面模式在窄屏上文字偏小，建议配合双指缩放；
- 合并版对 Termux 上游的改动集中在包名与 bootstrap 前缀，升级上游时需重做这两步。

---

## 七、许可证

本仓库自有代码以 **GPL-3.0** 发布，见 [`LICENSE`](LICENSE)。

各子项目许可不同（`termux/` 为 GPLv3、`tdsh-android/` 自有代码为 MIT 但打包了 GPLv2 的 proot），
分发 APK 时的义务见 [`NOTICE.md`](NOTICE.md)。
