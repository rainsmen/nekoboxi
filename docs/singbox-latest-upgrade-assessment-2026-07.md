# sing-box 最新版本更新评估

**评估日期**：2026-07-23
**项目基线**：`rainsmen/singbox@1.13.x-neko`（构建脚本固定分支，Go 1.24.7；项目 README 标注 Go 1.25 构建）
**上游状态**：官方稳定版 `v1.13.14`（2026-06-25）；最新开发版 `v1.14.0-alpha.50`（2026-07-22）。

## 结论

项目基线实际分叉于官方 `1.13.12`（fork 最近提交为 2026-05-31），因此尚未包含 `v1.13.14` 的稳定修复。当前不建议直接切换到 1.14 alpha。应先把 1.13.14 的修复移植到 fork，再以独立实验分支验证 1.14 的配置和 libbox API 变化。升级可行，但必须把 fork 的 Android 桥接、pidfd workaround、VLESS multiplex 修复、选择器回调、Tailscale endpoint 注册一起保留并回归测试。

## 主要更新（1.13.14 -> 1.14.0-alpha.50）

| 上游变化 | 对本项目的意义 | 建议 |
|---|---|---|
| DNS transport / route rule 重构，typed DNS 与新的 resolver/action 模型 | `ConfigBuilder` 生成的旧 DNS、TUN、direct override 字段可能被弃用或拒绝；属于最大兼容风险 | 必须适配后再启用，先做配置迁移和旧配置回读测试 |
| WireGuard 从 outbound 进一步 endpoint 化，相关 device/NAT API 变化 | 项目已注册 WireGuard endpoint，但 `libcore/box_include.go`、启动/停止生命周期和 Kotlin 配置映射都需核对 | 中高风险，单独分支验证 |
| 新增 OpenVPN、OpenConnect endpoint/outbound 能力 | Android UI、配置 Bean、构建标签目前没有对应入口；不能仅升级核心就获得可用功能 | 暂不接入；若有需求再做完整协议适配 |
| 新增 Snell 协议、Bridge、L3 forwarding、USB/IP、网络质量工具 | 不在项目现有协议列表或 Android 产品范围内 | 暂不接入 |
| 证书 provider / origin CA / ACME / 自定义 CA 能力增强 | 可改善 Android 证书信任和企业/自签证书场景 | 值得移植，但需先确认 libbox 暴露 API，再加 UI/存储 |
| 路由规则增强（rule-set 多 tag、interface/process/neighbor 匹配修复等） | 可提升分流准确性；部分规则可通过 custom config 先使用 | 低到中风险，优先移植已稳定的规则修复 |
| NaiveProxy HTTP/2、连接建立和慢开修复，TLS/QUIC/UDP 多项稳定性修复 | 直接改善现有 native Naive、Hysteria/TUIC 等链路 | 可随 1.13.x 稳定修复同步；不要与 cronet/NDK 变更捆绑 |
| Tailscale detour、网络 reset、OOM、tun race、GSO 等修复 | 项目已启用 Tailscale endpoint，Android 长时间运行和网络切换收益明显 | 高优先级回移并做 Android 10/网络切换回归 |
| Go 更新至 1.25.12（上游 alpha）及依赖升级 | 影响 gomobile、Cronet、NDK 链接和 pidfd workaround | 暂不跟随 alpha；先固定现有 Go/NDK 构建矩阵 |

上游在 1.13.14 到 alpha.50 之间约有 790 个文件变更、约 10 万行增删，不能按小版本替换视为 ABI 兼容升级。

## 与项目的耦合点

- `buildScript/lib/core/get_source.sh` 会 checkout `1.13.x-neko`，并恢复 `experimental/libbox/pidfd_android.go`；切换官方源码会丢失 fork 行为补丁。
- `libcore/box_include.go` 显式注册 endpoint、service、protocol；上游注册表变化会导致编译成功但运行时“unknown type”。
- Kotlin 的 `ConfigBuilder`/`SingBoxOptions` 仍生成旧 schema。1.14 DNS、TUN、route action 字段需要逐项迁移，不能只改版本号。
- 当前 native Naive 依赖 `with_naive_outbound`、Cronet 和 NDK 兼容处理。该链路曾出现 relocation 315 风险，应与核心升级分开验证。
- fork 自有行为至少包括 `DoNotSelectInterface`、selector UI callback、VLESS multiplex/flow 兼容、内置 geoip/geosite 路径和 Tailscale Android 处理，升级后必须逐项检查。

## 可落地的优先级

### P0：现在就做

1. 将 fork 分支同步到官方 `v1.13.14`，优先纳入 HTTP proxy auth、TUN NAT/route、DNS deadlock、Cronet ARM、Naive QUIC ALPN、VLESS packet encoding、sing-mux UDP、Tailscale 等修复，保留上述 Android patch。
2. 增加核心版本、配置 schema、endpoint 注册和 Tailscale/Naive smoke test。
3. 固定 Go、NDK、gomobile、Cronet 版本；核心同步与 native Naive 优化分开提交。

### P1：低风险收益

- 回移 DNS retry、tun close race、network reset、OOM、GSO、Tailscale detour、Naive HTTP/2 等 1.13 稳定修复。
- 暴露已兼容的 rule-set 多 tag、interface/IP 路由规则到 custom config 或小范围 UI。
- 评估 origin CA/custom CA 的 Android 存储与 libbox API，再实现证书管理。

### P2：独立实验项目

- 1.14 typed DNS / route action / TUN schema 全量迁移。
- WireGuard endpoint API 重构和生命周期回归。
- OpenVPN/OpenConnect/Snell 等新协议；只有在产品明确需要时投入。

## 验证门槛

升级分支至少应通过：`go test ./...`（libcore 与 sing-box）、四 ABI 的 libcore AAR 构建、Android 10/14 真机启动与网络切换、现有 SOCKS/VLESS/VMess/Trojan/Hysteria/TUIC/WireGuard/Tailscale/Naive 配置回归、旧配置迁移和自定义 config 校验。未通过前不要替换生产分支的 `COMMIT_SING_BOX`。

## 数据来源

- [SagerNet/sing-box releases](https://github.com/SagerNet/sing-box/releases)
- [v1.13.14](https://github.com/SagerNet/sing-box/releases/tag/v1.13.14)
- [v1.14.0-alpha.50](https://github.com/SagerNet/sing-box/releases/tag/v1.14.0-alpha.50)
- [sing-box changelog](https://sing-box.sagernet.org/changelog/)
- [项目构建脚本](../buildScript/lib/core/get_source.sh)
