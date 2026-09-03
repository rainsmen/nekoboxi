# sing-box 最新版本更新评估（2026-08）

**评估日期**：2026-08-20
**项目基线**：`sing-box/` 子模块 = 官方 `v1.13.14` + 本地补丁（naive 连接预热、Android `adapter.PlatformInterface` 注册、移除 `pidfd_android.go`）
**上游状态**：稳定线最新 `v1.13.19`；开发线最新 `v1.14.0-beta.17`
**构建标签**：`with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_tailscale`（`libcore/build.sh:21`）

本文承接 `docs/singbox-latest-upgrade-assessment-2026-07.md`。上次评估时基线尚停留在 1.13.12，现已完成到 1.13.14（提交 `069456c`），并同步外部 NaiveProxy 到 v150（提交 `ca8a6a2`）。

## 结论

分两步走：

1. **当前分支先升到 `v1.13.19`**，纯修复线，无配置破坏性变更，且能让核心内置 naive 版本与已外挂的 v150 插件对齐。这是现在最值得做的一步。
2. **另开实验分支验证 `v1.14.0-beta.17`**，优先验证三项对 Android 客户端收益最大的特性；1.14 仍在 beta，且本地补丁 rebase 成本明显高于 1.13.19。

## 一、1.13.14 → 1.13.19（低风险）

| 版本 | 内容 | 对本项目的意义 |
|---|---|---|
| 1.13.15 | Fixes and improvements | 常规修复 |
| 1.13.16 | **AnyTLS 客户端元数据默认清空** | 上游发现 AnyTLS 客户端会上传开源服务端根本不用的客户端元数据，已有厂商据此对用户做画像与区别对待。隐私向修复，对移动客户端直接有价值。如需自定义见上游 `manual/misc/anytls-client-metadata` |
| 1.13.18 | **naiveproxy 更新至 v150.0.7871.63-1** | 与本仓库 `ca8a6a2` 外部 NaiveProxy v150 对齐，消除核心内置版本与插件版本错配 |
| 1.13.19 | Fixes and improvements | 常规修复 |

无配置迁移工作量，建议直接在 `upgrade/singbox-1.13.14-naive-plugin` 分支上完成。

## 二、1.14 开发线特性筛选

### 强相关（Android 客户端直接受益）

| 特性 | 版本 | 说明 |
|---|---|---|
| Hysteria2 Chrome QUIC 指纹拟态 | beta.7 | 默认开启，抗握手指纹识别。**注意**：Chrome 不声明 Ed25519 支持，服务端使用 Ed25519 证书会握手失败，需 `disable_chrome_parrot` |
| TUN DNS 模式可定制 + 默认劫持接口 DNS | alpha.21 | 对应 1.13 升级中踩过的 naive/TUN 回环与 DNS 分流问题 |
| `package_name_regex` 路由/DNS/headless 规则项 | alpha.10 | Android 独有能力，按包名正则分流，本项目收益最高的一条 |
| 乐观 DNS 缓存 | alpha.11 | 弱网与冷启动体验 |
| DNS 查询超时选项 | alpha.19 | 同上 |
| TLS spoof 及多种 spoof 方法、路由规则动作支持 | alpha.13 / alpha.21 | 抗识别 |
| `source_mac_address`、TUN `include/exclude_mac_address` | alpha.1 | 局域网场景分流 |

### 中等相关（有价值但需额外 UI/配置工作）

| 特性 | 版本 | 说明 |
|---|---|---|
| 并行 DNS 竞速：`race` / 带 tag 的 `evaluate` / `speculative` | beta.1 | 降延迟效果显著，但配置模型复杂，`ConfigBuilder` 与 UI 侧改动量大 |
| 规则集多 tag 支持、新 UDP NAT 选项 | alpha.46 | 分流灵活性 |
| JSON Schema 支持（`$schema` 字段、`sing-box schema` 命令） | beta.2 | 可为应用内 JSON 配置编辑器提供补全与校验；上游已在 Android 客户端做了对应编辑器改进 |
| mDNS DNS server、`preferred_by` DNS 规则项、邻居主机名解析 | alpha.21 | 局域网解析场景 |
| Snell 协议 | alpha.38 | 协议扩展，需新增 Bean 与 UI |
| bridge outbound / `preferred_by` for bridge | alpha.40 / alpha.41 | 同上 |
| Tailscale Taildrop、Tailscale SSH server、`listen_port` | beta.15 / alpha.27 | 构建标签已含 `with_tailscale`，接入成本不高 |
| Hysteria2 gecko obfs、BBR profile、hop interval 随机化 | alpha.26 / alpha.8 | 抗封锁与拥塞控制 |
| Hysteria Realm 服务与 Hysteria2 NAT 穿透 | alpha.22 / alpha.41 | 场景较窄 |

### 基本无关（建议跳过）

OpenVPN 客户端与服务端、OpenConnect 客户端、Fortinet host check、AnyConnect SSO、Windows 客户端应用与 Windows TLS engine、Apple HTTP/TLS engine、Linux 桌面客户端、cloudflared inbound、ACME 证书提供者体系与 Cloudflare Origin CA、`sing-box api` CLI、sing-box Dashboard、网络命名空间支持、L3 转发、OpenWrt/Alpine APK 打包。

这些均为桌面端、服务端或企业 VPN 方向，与 NekoBox 的 Android 产品范围不重叠。

## 三、升级 1.14 的硬性成本

1. **规则集匹配语义修正（beta.1）**：合并式匹配现在仅限「规则集只含单条 `default` 规则且无 `invert`」的情形；其余被引用的规则集按 other-field 语义匹配，即其中任一规则自身匹配即成立。上游明确不视其为破坏性变更，但本项目内置规则与用户订阅规则集必须实测回归。
2. **1.14 仍在 beta**：`v1.14.0-beta.17` 尚未发布正式版。
3. **本地补丁 rebase 面**：`sing-box/` 上带有 naive 连接预热（`0e6b8a59`）、`adapter.PlatformInterface` 注册（`b30642b5`）、`pidfd_android.go` 移除（`871d070d`）三处补丁，跨大版本 rebase 的冲突风险显著高于 1.13.19。
4. 上次评估中列出的耦合点依然成立：`buildScript/lib/core/get_source.sh` 的 checkout 与 pidfd 恢复逻辑、`libcore/box_include.go` 的显式注册表（注册表变化会导致编译通过但运行时 unknown type）。

## 四、建议路径

1. 当前分支执行 `1.13.14 → 1.13.19`，保留三处本地补丁，回归 naive 插件链路与 TUN/DNS 分流。
2. 另开 `experiment/singbox-1.14` 分支跟踪 `v1.14.0-beta.17`，优先验证：
   - `package_name_regex` 规则项
   - TUN DNS 模式与接口 DNS 劫持
   - Hysteria2 Chrome QUIC 拟态（含 Ed25519 证书失败路径）
3. 待 1.14.0 正式版发布且上述三项验证通过后，再评估合并主线。

## 数据来源

上游 `docs/changelog.md`（`SagerNet/sing-box` 主分支，2026-08-20 拉取），以及 `git ls-remote --tags` 得到的 tag 列表。
