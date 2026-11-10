# ThBox for Android

[![API](https://img.shields.io/badge/API-29%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=29)
[![Releases](https://img.shields.io/github/v/release/rainsmen/nekoboxi)](https://github.com/rainsmen/nekoboxi/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

ThBox is a NekoBox for Android fork based on sing-box. This repository tracks the current Android app and cloud build setup used by this fork.

ThBox 是基于 NekoBox for Android 和 sing-box 的自用维护分支。当前仓库以本 fork 的 Android 客户端、libcore 构建和云端编译流程为准。

## 下载 / Downloads

[GitHub Releases](https://github.com/rainsmen/nekoboxi/releases)

[GitHub Actions Preview Builds](https://github.com/rainsmen/nekoboxi/actions/workflows/preview.yml)

本仓库不发布 Google Play 版本。请以 `rainsmen/nekoboxi` 的 Releases 或 Actions 构建产物为准，不要把上游 `MatsuriDayo/NekoBoxForAndroid` 的发布信息当成本 fork 的发布信息。

This fork does not publish a Google Play build. Use artifacts from this repository only.

当前 APK 最低要求 **Android 10 / API 29**，发布与预览包均为 **arm64-v8a**。
本分支 Preview 显示为 `ThBox Preview`，包名 `io.github.rainsmen.thbox.preview`，可与正式版共存；节点和设置分别保存，可通过应用内备份迁移。旧的同包名 Preview 不适用这一说明。

## 当前分支差异 / Fork Notes

- 应用显示名称为 `ThBox`，Android `applicationId` 为 `io.github.rainsmen.thbox`，可与原版 NekoBox/NB4A 共存；Gradle 项目名仍为 `NB4A`。
- Android 原生核心基于 sing-box v1.14.4，使用 `rainsmen/singbox` 的 `1.14.4-neko` 分支，以 git submodule 固定到具体提交，`buildScript/lib/core/get_source.sh` 检出并校验该提交。
- Tailscale 已作为 sing-box endpoint 启用，并保留 Android pidfd workaround，避免部分 Android 10 设备因 `pidfd_open` 被 seccomp 杀进程后反复连接/断开。
- 云端 libcore 构建使用 Go `1.25.14`（Go 1.26+ 与 sing 1.14 依赖的 go-json-experiment 不兼容）和 gomobile；Preview/Release workflow 的 cache key 覆盖 workflow、`buildScript` 与 `libcore` 状态，相关脚本变更会触发重新构建 `libcore.aar`。
- Native NaiveProxy outbound 当前未编入 libcore，原因是 cronet-go/Chromium 预编译对象在当前 NDK 链接链路下存在 relocation 问题；云端 APK 仍会通过 `download_naive.sh` 自动下载并打包 `arm64-v8a` 的 `libnaive.so`（与 `splits.abi` 一致，可用 `NAIVE_ABIS` 覆盖），属于外部 native 插件路径，不是 libcore 内置 sing-box outbound。
- 发布签名密钥不在仓库中，由 GitHub Secrets 提供，并通过 APK v3 密钥轮换从旧证书过渡到新证书，已安装的版本可直接覆盖升级。详见 [docs/release-signing.md](docs/release-signing.md)。

## 支持的代理协议 / Supported Proxy Protocols

内置 sing-box 能力：

- SOCKS (4/4a/5)
- HTTP(S)
- Shadowsocks
- VMess
- Trojan
- VLESS
- AnyTLS
- ShadowTLS
- TUIC
- Hysteria 1/2
- SSH
- Snell v4 / v6
- WireGuard endpoint
- Tailscale endpoint
- sing-box custom config / outbound

外部插件路径包括 NaiveProxy（云端打包 arm64 `libnaive.so`）、Trojan-Go、Mieru 和部分 Hysteria 兼容路径。插件能力取决于已打包或已安装的插件包，和本仓库内置 libcore 能力不是同一件事。

Built-in sing-box support includes SOCKS, HTTP(S), Shadowsocks, VMess, Trojan, VLESS, AnyTLS, ShadowTLS, TUIC, Hysteria 1/2, SSH, Snell v4/v6, WireGuard endpoint, Tailscale endpoint, and custom sing-box configs/outbounds. NaiveProxy is provided through the bundled external native plugin.

## 支持的订阅格式 / Supported Subscription Format

- 常见分享链接和订阅格式，例如 Shadowsocks、ClashMeta、v2rayN 等
- sing-box outbound / custom config

仅解析出站节点，并保留可展开的线性 detour 依赖；内嵌依赖是独立副本，可在节点设置中编辑。分流规则等完整客户端配置不会作为订阅规则集导入。策略组、缺失或循环依赖无法拆分时会明确提示使用完整自定义配置。

Only outbound nodes are imported from subscriptions. Routing rules and other full-client configuration fields are not imported as rule sets.

## 云端编译 / GitHub Actions Build

- Preview: `.github/workflows/preview.yml`
- Release: `.github/workflows/release.yml`
- Native core build: `./run lib core`
- Android package build: `app:assemblePreviewRelease` or `app:assembleOssRelease`

The libcore job uploads `app/libs/libcore.aar`, and the APK job downloads that artifact before Gradle packaging. Preview builds also run on every push to `feat/**` branches. The Tailscale pidfd workaround (`experimental/libbox/pidfd_android.go`) is committed in the `1.14.4-neko` sing-box branch. Release APKs are re-signed with key rotation by `buildScript/sign/rotate_sign.sh`; see [docs/release-signing.md](docs/release-signing.md) for the required secrets.

本地构建需要 JDK 17、Android SDK 35、NDK `28.2.13676358` 和 Go `1.25.14`：

```bash
git submodule update --init --checkout sing-box libneko libcore/gomobile
./run lib core
./run init action gradle
bash download_naive.sh
./gradlew app:testOssDebugUnitTest app:lintOssRelease app:assemblePreviewRelease
```

Go 集成验证消费上一步生成的正常代理模式配置：

```bash
cd libcore
THBOX_CONFIG_FIXTURES="$PWD/../app/build/generated/config-fixtures/testOssDebugUnitTest" go test -race -ldflags=-checklinkname=0 -tags with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_tailscale .
```

工具链和规则资产版本固定在构建脚本及 `buildScript/lib/assets.lock`。自动测试、核心启动和设备验证的覆盖范围见 [本轮改进记录](docs/reliability-improvement-2026-11.md)。

## 上游与 Credits

This fork is based on [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid). General usage documentation from upstream may still be useful, but repository links, releases, build behavior, and supported built-in features should follow this README.

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [rainsmen/singbox](https://github.com/rainsmen/singbox)

Android GUI:

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
