# 许可证与第三方声明

本仓库包含多个来源不同的子项目，各自适用不同许可证。根目录 `LICENSE`（GPLv3）
覆盖本仓库自有代码，**不改变各子项目原有的许可**。

| 目录 | 许可证 | 说明 |
| --- | --- | --- |
| `android/` | GPL-3.0 | 本仓库自有代码（外壳版） |
| `termux/` | GPL-3.0 | 基于 termux-app 的改造；上游为 GPLv3 only，本仓库的修改同样以 GPLv3 发布 |
| `tdsh-android/` | MIT | 自有代码为 MIT，见 [`tdsh-android/LICENSE`](tdsh-android/LICENSE)；但打包了 GPLv2 的 proot 等组件，见 [`tdsh-android/THIRD-PARTY.md`](tdsh-android/THIRD-PARTY.md) |
| `dist/` | 随产物 | 本地构建产物（不入库），许可证取决于其包含的组件 |

## 分发 APK 时的义务

- **合并版（`com.dshapp`）** 内含 Termux 运行时（GPLv3）。分发 APK 时须提供对应源码，
  本仓库即为该源码；若修改了 `termux/` 下的代码，须一并公开。
- **tdsh-android（`com.dsh.tdsh`）** 内含 proot（GPLv2）等组件，须随 APK 附带
  `tdsh-android/THIRD-PARTY.md` 与 `tdsh-android/LICENSES/` 下的许可证全文。
- 两个 APK 都打包了 Termux 官方软件源中的二进制（bootstrap），其许可证见各软件包自身。

## 上游项目

- termux-app — <https://github.com/termux/termux-app>（GPLv3）
- termux-packages — <https://github.com/termux/termux-packages>
- proot — <https://github.com/proot-me/proot>（GPLv2）
- DeepSeek Harness — 由 DeepSeek 提供，按其自身条款使用

## 签名说明

仓库内 `android/keystore/debug.keystore` 与 `termux/app/testkey_untrusted.jks`
均为**公开的测试签名**，仅用于产出可安装的调试包。任何人都能用它们签出同包名的
覆盖安装包，请勿用于正式分发。
