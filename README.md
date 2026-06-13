# ThBox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/rainsmen/nekoboxi)](https://github.com/rainsmen/nekoboxi/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

ThBox is a NekoBox for Android fork based on sing-box. This repository tracks the current Android app and cloud build setup used by this fork.

ThBox 是基于 NekoBox for Android 和 sing-box 的自用维护分支。当前仓库以本 fork 的 Android 客户端、libcore 构建和云端编译流程为准。

## 下载 / Downloads

[GitHub Releases](https://github.com/rainsmen/nekoboxi/releases)

[GitHub Actions Preview Builds](https://github.com/rainsmen/nekoboxi/actions/workflows/preview.yml)

本仓库不发布 Google Play 版本。请以 `rainsmen/nekoboxi` 的 Releases 或 Actions 构建产物为准，不要把上游 `MatsuriDayo/NekoBoxForAndroid` 的发布信息当成本 fork 的发布信息。

This fork does not publish a Google Play build. Use artifacts from this repository only.

## 当前分支差异 / Fork Notes

- 应用显示名称为 `ThBox`，Android `applicationId` 为 `io.github.rainsmen.thbox`，可与原版 NekoBox/NB4A 共存；Gradle 项目名仍为 `NB4A`。
- Android 原生核心使用 `rainsmen/singbox` 的 `1.13.x-neko` 分支，并由 `buildScript/lib/core/get_source.sh` 在云端构建前拉取。
- Tailscale 已作为 sing-box endpoint 启用，并保留 Android pidfd workaround，避免部分 Android 10 设备因 `pidfd_open` 被 seccomp 杀进程后反复连接/断开。
- 云端 libcore 构建使用 Go `^1.25` 和 gomobile；Preview/Release workflow 的 cache key 覆盖 workflow、`buildScript` 与 `libcore` 状态，相关脚本变更会触发重新构建 `libcore.aar`。
- Native NaiveProxy outbound 在 `feature/native-naive-poc` 分支中已启用，用于验证 sing-box 原生 `type: naive` 路径；该改动会触发 libcore 全量构建，并重新暴露 cronet-go/NDK native 链接风险。

## 支持的代理协议 / Supported Proxy Protocols

内置 sing-box 能力：

- SOCKS (4/4a/5)
- HTTP(S)
- Shadowsocks
- VMess
- Trojan
- NaiveProxy
- VLESS
- AnyTLS
- ShadowTLS
- TUIC
- Hysteria 1/2
- SSH
- WireGuard endpoint
- Tailscale endpoint
- sing-box custom config / outbound

外部插件路径包括 Trojan-Go、Mieru 和部分 Hysteria 兼容路径。插件能力取决于已打包或已安装的插件包，和本仓库内置 libcore 能力不是同一件事。

Built-in sing-box support includes SOCKS, HTTP(S), Shadowsocks, VMess, Trojan, NaiveProxy, VLESS, AnyTLS, ShadowTLS, TUIC, Hysteria 1/2, SSH, WireGuard endpoint, Tailscale endpoint, and custom sing-box configs/outbounds.

## 支持的订阅格式 / Supported Subscription Format

- 常见分享链接和订阅格式，例如 Shadowsocks、ClashMeta、v2rayN 等
- sing-box outbound / custom config

仅解析出站节点。分流规则等完整客户端配置不会作为订阅规则集导入。

Only outbound nodes are imported from subscriptions. Routing rules and other full-client configuration fields are not imported as rule sets.

## 云端编译 / GitHub Actions Build

- Preview: `.github/workflows/preview.yml`
- Release: `.github/workflows/release.yml`
- Native core build: `./run lib core`
- Android package build: `app:assemblePreviewRelease` or `app:assembleOssRelease`

The libcore job uploads `app/libs/libcore.aar`, and the APK job downloads that artifact before Gradle packaging. The Tailscale pidfd workaround is restored during `get_source.sh`, so a fresh GitHub runner can build correctly even if the checked-out sing-box branch no longer contains `experimental/libbox/pidfd_android.go`.

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
