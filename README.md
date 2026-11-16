# DSH Mobile

把 DeepSeek Harness（DSH）搬到安卓上的两个产物，均由 GitHub Actions 构建并发布：

**下载地址：https://github.com/fenzirong/dsh-mobile/releases/latest**

| 产物 | 包名 | 体积 | 说明 |
| --- | --- | --- | --- |
| `DSH-Mobile-shell.apk` | `com.dsh.mobile` | 2.8 MB | 纯前端外壳，连接 Termux 里的 DSH 主机 |
| `DSH-Mobile-merged-arm64.apk` | `com.dshapp` | 36.8 MB | 内置 Termux 的合并版，一个 APK 同时含终端与前端（仅 arm64-v8a） |

两者共用同一套前端逻辑：**DSH 的 Web GUI 原样跑在 WebView 里**，所以所有已安装的
客户端插件（侧边栏、终端、文件、插件市场、日程、子代理……）都自动生效，不需要为安卓重写。

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
检查环境 → 写入镜像源（若已选） → apt update → 装 nodejs-lts + npm
        → npm i -g @deepseek-ai/dsh → 启动 dsh web → 抓取 token URL → 加载界面
```

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
| check | 0 → 8% |
| mirror | 8 → 12% |
| apt-update | 12 → 30% |
| install-node | 30 → 60% |
| install-dsh | 60 → 85% |
| start | 85 → 95% |
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

本地构建（需 ≥ 4 GB 可用内存 + 充足磁盘 I/O）：

```bash
cd termux
# 需要：JDK 17、SDK platform 36、build-tools 35.0.0、NDK 29.0.14206865
./gradlew assembleDebug
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
  → dpkg-deb -b -Zxz --root-owner-group 重新打包
  → dpkg 安装已适配的包
```

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
├── android/                 # 外壳版工程（com.dsh.mobile）
│   ├── app/src/main/java/com/dsh/mobile/
│   │   ├── MainActivity.java    # WebView 外壳、鉴权、401 重授权、下载/上传
│   │   ├── Prefs.java           # 连接配置持久化
│   │   └── TermuxBridge.java    # RUN_COMMAND 拉起后端
│   └── keystore/debug.keystore  # 工作区内调试签名
├── termux/                  # 合并版工程（com.dshapp）
│   ├── app/src/main/java/com/termux/app/DshActivity.java
│   ├── app/src/main/java/com/dsh/mobile/{DshPrefs,DshMirrors,DshBackend}.java
│   ├── app/src/main/cpp/bootstrap-aarch64.zip   # 已改写前缀
│   └── ...
└── dist/                    # 构建产物
```

---

## 六、已知限制

- 合并版只打 `arm64-v8a`（现代手机通用），如需 32 位需另备对应 bootstrap；
- 令牌与 `dsh web` 进程同生命周期，重启后端需重新粘贴 URL；
- 桌面模式在窄屏上文字偏小，建议配合双指缩放；
- 合并版对 Termux 上游的改动集中在包名与 bootstrap 前缀，升级上游时需重做这两步。
