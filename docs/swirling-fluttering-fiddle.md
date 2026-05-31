# NekoBox 功能升级 · 实施计划

## 📌 状态快照（2026-05-31 收尾，恢复测试用）

**当前基线提交**（均已推送）：
- 主仓库：`ce9a9c5` → `rainsmen/nekoboxi:main`（remote `custom`）
- sing-box：`871d070` → `rainsmen/singbox:1.13.x-neko`
- 最新 APK：CI run **26711252542** 的 `APKs` artifact（27 MB，含 libnaive.so）

**已完成（构建级全绿，运行期部分验证）**：
- Phase 0–2：绿色构建基线；ECH/TLS Fragment/Tailscale/ICMP/全局CA；FakeDNS 默认关。
- 三个运行时阻断已修：① inbound sniff→rule action；② tun `inet4/6_address`→`address`；③ libcore 注册 `adapter.PlatformInterface`（netlink ban）。
- 两个路由/DNS 根因已修：④ fakeip 仅 A/AAAA（HTTPS-RR 不再报错）；⑤ `route.final=proxy`（1.13 空 final 兜底变 direct）。
- Phase 3 naive：**打包外部插件**方案落地（非原生 cronet）。

**▶️ 下次恢复后要测的两件事**（用 run 26711252542 的 APK）：
1. **普通节点**（vmess/trojan/vless）：**不开「中国IP规则」**也能正常浏览网页 → 验证 route.final 根因修复。
2. **naive 节点**：能连、能上网（插件子进程起、SOCKS 端口映射通）。naive 出错先看 Logcat 的 GuardedProcessPool/`outbound/socks` 行；插件调用是 `libnaive.so <配置文件>`（位置参数），配置见 `NaiveFmt.buildNaiveConfig`。

**未完成（已记录，按需做）**：WireGuard 节点 → endpoint 迁移（WG 用户才需）；外部插件 direct 入站 override 核查（niche）。

**恢复操作备忘**：推送用用户的 GitHub token（每次现给，不入库）；CI 靠 `workflow_dispatch` 触发 `preview.yml`（两个 workflow 都只手动触发）；改 `.github/workflows/*` 或 `libcore`/`sing-box` 会使 libcore 缓存失效 → 完整重编 Go（~10min），纯 app 改动则缓存命中（~3–4min）。

> 详细逐条经过见下方「实施记录」。

## 🩹 2026-05-31（续10）· 真机问题修复轮次

**问题2「浏览不通」= FakeDNS（已确认并解决）**：关 FakeDNS 后冷启动也正常。根因是 fakeip + Android 强制开启的持久化 cache.db 在冷启动复用旧映射时状态错乱（1.13 fakeip 自身毛病）。**FakeDNS 默认已关**（`DataStore.enableFakeDns { false }`，commit `1a21a13`）。先前「开中国IP规则就好」实为切换规则触发的 reload 副作用，非规则内容。

**问题1「naive 测速超时」= TUN 路由环路（commit `1086edf` 修复）**：
- 根因：`Plugins.getPlugin()` 有「internal so」兜底——没装外部插件时**永远返回 `moe.matsuri.exe.` provider**，致 `isUsingMatsuriExe()` 对任何插件恒为 true → ConfigBuilder:443 `needExternal=false` → **跳过上游 mapping** → naive 被配成直连真实服务器。而 cronet 版 naive **不像 matsuri Go 插件那样走 `protect_path` 保护 socket**（mieru 设 `MIERU_PROTECT_PATH=protect_path`）→ naive 上游 socket 进 TUN → 被 `route.final=proxy` 送回 naive → 无限环路（日志实证：`[libnaive] Connection to 67.230.169.71:4431` 反复 + TUN 抓回）。
- 修复：ConfigBuilder:443 加 `bean !is NaiveBean &&`，**强制 naive 走 mapping 路径**（连 `127.0.0.1:mappingPort` → `direct` 入站 → box 受保护拨号器出网，回环不进 TUN）。已核实 1.13 仍支持 `direct` 入站 + `override_address/port`（task #6 完成）。

**中国IP规则默认打开（commit `1086edf`）**：`ProfileManager.getRules()` 首次种子里，把 cn 的 `geosite:cn`/`geoip:cn` 绕过规则建成 `enabled = country == "cn"`（ir/ru 仍默认关）。注意：仅对**全新安装/规则库为空**生效；既有安装需手动开或重置规则。

> CI run 26715673922（`1086edf`，缓存命中）绿。**待真机验证**：① naive 节点能连/能上网（不再环路）；② 新装默认即中国直连+境外代理。

## Context（背景与目标）

NekoBox 上游（MatsuriDayo）停留在 sing-box 1.12.x。本仓库已有前序 AI 工具做的 **1.13.12 部分迁移**（`sing-box/` = 官方 1.13.12 + 薄 shim；`libcore/` 已适配 1.13 注册表架构），但 **GitHub Actions 编译失败**——根因是额外把 cronet 原生 NaiveProxy 编进了 libcore，触发 `R_AARCH64_PREL32` 链接错误。

经与官方 1.13.12 逐文件核对，作者魔改 **不含任何自定义 option 字段**（配置 schema 与官方一致），现有代码**可复用**。目标：先把现有 1.13.x 稳定到**绿色构建基线**，再在其上增量实现 7 项功能，**保持在 1.13.x（不上 1.14）**。

**已确认决策**：
- 策略：复用现有 + 稳定化（非推倒重来）
- 功能：① TLS Fragment ② ECH 开关 ③ 证书管理 ④ ICMP/ping ⑤ 新路由规则项 ⑥ Tailscale 增强 ⑧ 原生 Naive
- 不做：⑦ 新 typed DNS（即不上 1.14，省掉 ConfigBuilder DNS 大改）
- Naive：Phase 0 先禁用换绿色构建；Phase 3 专攻 cronet 原生链接，搞不定回退外部插件

**功能可用性已核实**（本地 sing-box 1.13.12）：TLS Fragment ✅(`option/tls.go`+`rule_action.go`)、ECH ✅(Android 模型已存在)、ICMP ✅(`route/route.go` 用 `N.NetworkICMP`，限 TUN→direct/WG/tailscale)、新路由项 ✅(`option/rule.go` NetworkInterfaceAddress)、Tailscale ✅(`option/tailscale.go` 全字段)、原生 Naive ✅(`option/naive.go` 全字段，仅构建受阻)、证书管理 ✅基础(`option/certificate.go`；"修复 Android 证书读取"为 1.14，本期不做)。

---

## Phase 0 — 稳定化（绿色构建，地基，必做）

### 0.1 解耦原生 naive（消除 CI 杀手）
| 文件 | 改动 |
|---|---|
| `libcore/build.sh` | 从 `-tags` 移除 `with_naive_outbound`；把 `-ldflags` 的 `-extldflags="-Wl,--allow-shlib-undefined -Wl,--undefined-version"` 还原为 `-ldflags='-s -w'`（对齐上游 `/tmp/nb4a/libcore/build.sh`） |
| `libcore/box_include.go:86` | 删除/注释 `registerNaiveOutbound(registry)` 调用 |
| `libcore/go.mod` | 移除 cronet-go 及其平台库依赖块（约 77–107 行）；`go mod tidy` |

> `box_include_naive_stub.go`（`!with_naive_outbound`）会让 `naive` 出站返回错误——naive 配置文件**暂不可用**（已确认接受）。app 的 `NaiveFmt.kt` 当前生成原生 `type:naive`，Phase 3 修复构建后即自动恢复。

### 0.2 补齐 3 个缺失 patch（已核实 1.13 官方不自带，确需补）
样板可直接参考 `/tmp/matsuri-singbox/`（上游 1.12.19-neko）对应文件：

1. **`sing-box/common/dialer/default.go`**：新增 `var DoNotSelectInterface = false`（约 25 行）；在 `DialContext`(~248) 与 `ListenPacket`(~318) 的判断改为 `if DoNotSelectInterface || d.networkStrategy == nil`。随后在 `libcore/box.go:31` 取消注释 `dialer.DoNotSelectInterface = true`。
2. **`sing-box/protocol/tun/`**：从 `/tmp/matsuri-singbox/protocol/tun/` 复制 `fix_gvisor.go` + `fix_gvisor_stub.go`；在 `inbound.go` 的 `Close()` 首行加 `t.fixGvisorClose()`。
3. **`sing-box/protocol/vless/outbound.go`**：在 `vless.NewClient(...)` 调用前（~89 行）加：
   ```go
   muxOpts := common.PtrValueOrDefault(options.Multiplex)
   if muxOpts.Enabled { options.Flow = "" }
   ```
   并把 client 创建改用 `muxOpts`。

> 已就位无需动：`box.go`(PlatformLogWriter)、`selector.go`(回调)、`rule_set_local.go`(geoip/geosite)、`tun/hook.go`、`boxapi`/`nekoutils`（shim 健康、无 API 漂移）、依赖（sing v0.8.10、replace 指向本地 sing-box/libneko，go.sum 齐全）。

### 0.3 构建验证（基线达绿）
1. **先确认工具链**（实施第一步）：`go version`(需 1.24+)、`java -version`、`ANDROID_NDK_HOME`、`$(go env GOPATH)/bin/gomobile-matsuri`、`./gradlew`。缺 gomobile-matsuri 则跑 `libcore/init.sh` 安装。
2. **Go 层**：在 `sing-box/` 与 `libcore/` 跑 `go build ./...` / `go vet`，先消灭编译错误。
3. **整链**：`cd libcore && ./build.sh` 产出 `.aar` → `./gradlew :app:assembleDebug`；本地工具链不全则**推 CI 为准**。
4. **冒烟**：能启动 VPN、能连一个 vmess/trojan 节点、分流（direct/proxy）正常、URLTest 出延迟。
> ⇒ 产出可信、可编译、可运行的 1.13.x 基线。后续每个 Phase 完成后都回到此验证。

---

## 通用改动模式（后续功能复用，仅此处说明一次）

新增"映射 sing-box option 字段的可配置项"，依次改 6 处（**JSON tag 必须与 sing-box option 的 json tag 逐字一致**）：
1. `app/.../moe/matsuri/nb4a/SingBoxOptions.java` — 对应 data class 加字段
2. `app/.../fmt/<proto>/<X>Bean.java` — 加字段 + `initializeDefaultValues()` + 序列化
3. `app/.../fmt/<proto>/<X>Fmt.kt` — `buildSingBox...` 里映射 bean→option
4. `app/.../fmt/ConfigBuilder.kt` — 全局/路由类配置在此生成
5. `app/src/main/res/xml/<x>_preferences.xml` — 加 `SwitchPreferenceCompat`/`EditTextPreference`
6. `app/.../ui/profile/<X>SettingsActivity.kt` — `pbm.add(PreferenceBinding(Type.Switch/Text, "field"))`

**样板**：`TailscaleSettingsActivity.kt` + `tailscale_preferences.xml` + `TailscaleBean.java` + `TailscaleFmt.kt`（`PreferenceBindingManager` 模式）。
**逃生舱**：临时/小众字段可直接用 `_hack_custom_config` 合并 JSON，免改 Bean/UI（见 `NaiveFmt.kt` 的 `CustomSingBoxOption` 用法）。

---

## Phase 1 — 快速见效

### 1.1 ECH 出站开关（最低成本）
- sing-box ✅、`SingBoxOptions.OutboundECHOptions` ✅ 已存在、`Constants.kt` 已预留 `Key.SERVER_ECH_CATEORY`。
- 仅需：在出站 TLS 设置 UI 加 ECH 开关 → `ConfigBuilder`/各 Fmt 在 `OutboundTLSOptions.ech.enabled=true` 时下发。核对 ECH config/configPath 字段映射。

### 1.2 TLS Fragment（抗审查）
- sing-box ✅：`option/tls.go`(OutboundTLSOptions.Fragment/FragmentFallbackDelay/RecordFragment) 与 `option/rule_action.go`(TLSFragment*)。
- **推荐**：做成**路由动作级全局开关**（`rule_action` 的 TLSFragment，针对被墙 SNI），在路由设置加开关 → `ConfigBuilder` 生成 route rule action；可选再加每出站 TLS 级开关。
- 需在 `SingBoxOptions.OutboundTLSOptions`（约 1896–1926 行）补 `fragment`/`fragment_fallback_delay`/`record_fragment` 字段。

---

## Phase 2 — 路由与 Tailscale

### 2.1 新路由规则项
- sing-box ✅ `option/rule.go`（NetworkInterfaceAddress/DefaultInterfaceAddress）。
- 在 `SingBoxOptions.DefaultRule`(1122–1189) 与 `DefaultDNSRule` 补字段；路由规则编辑 UI 暴露；`ConfigBuilder` 生成。

### 2.2 Tailscale 增强
- sing-box ✅ `option/tailscale.go:13-32`（advertise_tags/routes/exit_node、relay_server_port、system_interface 等）。
- 按"通用模式"补全：`SingBoxOptions.TailscaleEndpointOptions`(858–870) 缺的 advertise_*/relay_* → `TailscaleBean.java` → `TailscaleFmt.kt` → `tailscale_preferences.xml` → `TailscaleSettingsActivity.kt`。

### 2.3 ICMP / ping over TUN
- sing-box ✅ 路由层（`route/route.go` 用 `N.NetworkICMP`）；**限 TUN→direct/WG/tailscale**，无法穿 vmess/ss 等代理（如实告知用户预期）。
- 实施：确认 `option/rule.go` 的 `network` 接受 `"icmp"`、TUN 入站捕获 ICMP（sing-tun）；在路由/TUN 设置加一个"隧道内 ICMP"开关 → `ConfigBuilder` 生成 `network:["icmp"]` 规则指向 direct。

### 2.4 证书管理（基础：自定义 CA）
- sing-box ✅ `option/certificate.go`（store/certificate/certificate_path/certificate_directory_path）。Android 侧**无** CertificateOptions 类。
- 实施：在 `SingBoxOptions` 新增 `CertificateOptions` data class；全局设置加"自定义信任 CA / 证书路径"项；`ConfigBuilder` 顶层 `certificate` 字段下发。
- **不做**：1.14 的"修复 Android 系统证书读取"+ Mozilla 内置列表（如实标注为后续/上 1.14 才有）。

---

## Phase 3 — 原生 Naive（cronet，高风险，单独立项）

- 目标：解决 `R_AARCH64_PREL32`（relocation 315）后重新启用 `with_naive_outbound`。
- 排查方向：NDK 版本（27 vs 26）、cronet-go 版本钉定、正确的 `-extldflags`/ABI、cronet 预编译库与目标架构匹配。
- app 的 `NaiveFmt.kt` 已生成原生配置，构建一通基础 naive 即可用；可选：在 `NaiveBean`/`NaiveFmt` 暴露 `quic`/`quic_congestion_control`（`option/naive.go` 已支持 BBR/BBRv2 等）。
- **回退**：若 cronet 在合理投入内仍无法链接，则改走外部插件 naive（恢复 app 插件路径），保证 naive 可用。

---

## 验证方案（每 Phase 通用）
1. **工具链就绪**：见 0.3。本地不全则以 **GitHub Actions 为权威**。
2. **Go 层**：改动 sing-box/libcore 后 `go build ./...` + `go vet`。
3. **构建**：`libcore/build.sh` → `./gradlew :app:assembleDebug`（或推 CI）。
4. **功能冒烟**（按 Phase）：ECH/Fragment→抓包确认 ClientHello 行为；路由项→规则命中日志；Tailscale→连入 tailnet 并通过 advertise 路由；ICMP→TUN 下 `ping` 有回应；证书→自定义 CA 节点握手成功。
5. **回归**：每 Phase 后重跑 Phase 0 冒烟，确保未破坏既有连通/分流。

## 风险与注意事项
- **本地全量构建可能不可行**（需 NDK27 + gomobile-matsuri + SDK + gradle）；以 CI 为准。实施首步先确认工具链。
- **原生 naive 可能在合理投入内无法解决**（git 历史多次失败）；已约定回退外部插件。
- **证书管理**仅覆盖 1.13.12 的自定义 CA；Android 证书读取修复属 1.14，本期不做。
- **ICMP 范围受限**（仅 TUN→direct/WG/tailscale）。
- 每次给 `SingBoxOptions.java` 加字段，务必与 sing-box `option` 的 JSON tag 逐字对齐，否则配置静默失效。
- 建议每个 Phase 一个独立分支/提交，便于 CI 二分定位回归。

---

## 实施记录（进展与对计划的偏差）

**2026-05-31**

**Phase 0.1（解耦 naive）✅**：仅改 `libcore/build.sh`（去 `with_naive_outbound`、还原 `-ldflags='-s -w'`）。
- 偏差：`box_include.go:86` 调用**保留**（`box_include_naive_stub.go` 已是 no-op，Phase 3 重启用零改动）。
- 偏差：`libcore/go.mod` cronet-go 块**不手删**（均 `// indirect`，不编译 naive 即不链接；手删会与 go.sum 失同步）。清理交给 `go mod tidy`（待 Bash）。

**Phase 0.2（补 patch）✅/⏭️**：
- Patch A `DoNotSelectInterface` ✅：`sing-box/common/dialer/default.go`（var + DialContext/ListenPacket 两处守卫）+ `libcore/box.go`（恢复 dialer import + 取消注释 init）。
- Patch C vless mux flow ✅：`sing-box/protocol/vless/outbound.go`。
- Patch B gvisor ⏭️**跳过**：sing-tun v0.8.9 `GVisor.Close()` 已自带 `endpoint.Attach(nil)`；且 matsuri 镜像结构体缺 `inet4Address/inet6Address`，照搬会内存错位崩溃。改为运行期验证（见任务列表）。

**待 Bash 恢复补做**：`go build ./...`(sing-box+libcore) + `go mod tidy` + gradle/CI 验证（Phase 0.3）。

**Phase 1.1（ECH）✅ 已是现成**：审计发现 ECH 早已完整实现——`StandardV2RayBean`(enableECH/echConfig) + `V2RayFmt.kt:615` 构建 + `standard_v2ray_preferences.xml:184` UI + `StandardV2RaySettingsActivity` 绑定。无需改动，仅需运行期验证。

**Phase 1.2（TLS Fragment）✅ 已实现**（按 ECH 模式，per-outbound）：
- `StandardV2RayBean.java`：加 `enableTLSFragment`/`tlsFragmentFallbackDelay`，序列化 **version 5→6**（末尾追加 + `version>=6` 守卫）。
- `SingBoxOptions.java`：`OutboundTLSOptions` 加 `fragment`/`fragment_fallback_delay`/`record_fragment`。
- `V2RayFmt.kt`：`buildSingBoxOutboundTLS` 在 `enableTLSFragment` 时下发 `tls.fragment`。
- `standard_v2ray_preferences.xml`：securityCategory 加开关+延迟输入（字面标题，免改 strings）。
- `StandardV2RaySettingsActivity.kt`：加两个 PreferenceBinding。

> ⚠️ **本会话全程 Bash 分类器不可用 → 以上改动均未经构建验证**。需用户侧 `go build` + gradle/CI 确认编译通过（尤其 StandardV2RayBean 序列化 version 升级，务必用旧配置回归测试）。

**Phase 2 ✅（按"先做完再验证"推进）**：
- 2.1 路由接口IP匹配 — **模型级**：`SingBoxOptions.DefaultRule` 加 `interface_address`/`network_interface_address`/`default_interface_address`（`java.util.Map`）。经路由规则"自定义配置"逃生舱（ConfigBuilder:599 合并 rule.config）可用；专用 UI 暂缓（TypedMap 复杂、移动端价值低）。
- 2.2 Tailscale 增强 — ✅ **全链路**：`TailscaleBean`(序列化 v1→v2，加 advertiseRoutes/advertiseTags/advertiseExitNode/relayServerPort) + `SingBoxOptions.TailscaleEndpointOptions` + `TailscaleFmt.kt` + `tailscale_preferences.xml` + `TailscaleSettingsActivity.kt`。
- 2.3 ICMP — ✅ `arrays.xml` 的 route_protocol 加 icmp；ConfigBuilder:552 已透传 `network=listOf(rule.network)`。限 TUN→direct/WG/tailscale，需运行期验证。
- 2.4 证书管理 — **模型级**：`SingBoxOptions` 加 `CertificateOptions` + `Options.certificate`。每服务器证书早已支持（tls.certificate）；全局 CA 经 `globalCustomConfig` 逃生舱可用；专用全局 UI 暂缓。

**额外修复（潜在编译错误）**：`PreferenceBinding.kt` 的 `Type` 缺 `Switch`，但 `TailscaleSettingsActivity` 用了 `Type.Switch`（前序 AI 引入的未解析引用，会让 app 编译失败）→ 加 `const val Switch=4` + 两个 `when` 分支，根因修复。

**构建依赖修复**：`get_source_env.sh` 的 `COMMIT_SING_BOX` 由钉死 commit 改为跟踪分支 `1.13.x-neko`（否则 CI 用旧 sing-box，`box.go` 引用的 `DoNotSelectInterface` 不存在 → 编译失败）。

**Phase 3 原生 Naive — 暂缓**：重新启用 `with_naive_outbound` 会再次引入 cronet `R_AARCH64_PREL32` 链接失败、破坏即将进行的构建验证；它需要构建迭代（NDK/cronet 版本试错），应在绿色基线确认后单独进行。

**多仓库推送（验证时需要）**：① `./sing-box` 提交 dialer/vless 改动 → push 到 rainsmen/singbox 的 `1.13.x-neko` 分支；② 主仓库提交 app/libcore/buildScript 改动 → push 到 rainsmen/nekoboxi（remote `custom`）；③ Actions 手动触发（workflow_dispatch）。

---

**2026-05-31（续）· Phase 0.3 绿色构建基线 ✅ 达成**

从异常退出的会话 `7d484d2f` 还原出中断点：当时卡在 GitHub Action 的 gradle 报错（`:app:compilePreviewReleaseKotlin` 失败，5 个 Kotlin 编译错误），随后 Bash/API 不稳定导致会话异常退出，修复未完成。

**根因 = 前序 AI（commit `43294f2`）的烂尾合并，2 处：**
1. `database/ProxyEntity.kt` `putByteArray()`：`TYPE_CONFIG -> configBean`（丢失赋值）+ 重复的 `TYPE_TAILSCALE` 分支错用 `KryoConverters.configDeserialize`（返回 `ConfigBean`）赋给 `tailscaleBean`（`TailscaleBean`）→ 类型不匹配。修复：合并为 `TYPE_CONFIG -> configBean = configDeserialize(...)` + `TYPE_TAILSCALE -> tailscaleBean = tailscaleDeserialize(...)` 两条正确分支。
2. `SingBoxOptions.MyOptions`：缺顶层 `endpoints` 字段（`ConfigBuilder.kt:242/377` 引用，1.13 endpoint 架构）。修复：加 `public List<Endpoint> endpoints;`。

> 顺带核实：计划提到的另一潜在错 `Type.Switch` 实为已修好（`proxy/PreferenceBinding.kt:14 const val Switch=4`，非 `ui/` 路径）。

**提交 & 验证**：commit `1dcafd4` → push `rainsmen/nekoboxi:main`（`9daab98..1dcafd4`）→ workflow_dispatch 触发 `preview.yml`。
- 结果：**Native Build (LibCore) ✅ + Build OSS APK ✅**，产出 `APKs`(≈26 MB)。
- LibCore job **缓存命中**（本次仅改 app 层），跳过 Go 构建 → 故 sing-box 两个未提交文件（`pidfd_android.go` 的 `//go:build !go1.24`、`go.mod` cronet 降级）对本次运行无影响；**注意 CI 用 Go 1.25**，`!go1.24` tag 会使该文件在 1.25 下被排除——待 libcore 缓存失效（改动 libcore/sing-box）时再决策是否推送。
- Run: https://github.com/rainsmen/nekoboxi/actions/runs/26707257009

> ⇒ **Phase 0/1/2 全部代码改动首次编译通过并产出 APK**。但仍为「构建级」验证，**运行期行为未测**。

---

**2026-05-31（续2）· P1 专用 UI：2.4 全局 CA ✅ / 2.1 决策跳过**

- **2.1 路由接口IP 专用 UI — 决策跳过**（用户拍板）。原因：sing-box 字段为 `TypedMap[接口名→CIDR列表]`，移动端做全笨重、价值低；且即便只做 `default_interface_address` 也需给 `RuleEntity` 加字段 → 动 Kryo 序列化版本（旧规则库回归风险）。**路由规则已有 `serverConfig` 自定义 JSON 逃生舱**（`EditConfigPreference` → ConfigBuilder 合并 rule.config），高级用户可直接填 `interface_address` 等。模型层字段（`DefaultRule.interface_address/...`）已就位，无需再动。
- **2.4 全局自定义 CA — ✅ 完整 UI**（commit `dd6a226`，CI 绿）。走全局 `configurationStore`（无 Kryo，零回归风险），改 6 处：
  1. `Constants.kt`：`CERTIFICATES = "certificates"`
  2. `DataStore.kt`：`var certificates by configurationStore.string(...)`
  3. `global_preferences.xml`：general 分类下 `EditTextPreference`（字面标题，免改 strings）
  4. `SettingsPreferenceFragment.kt`：`findPreference` + `onPreferenceChangeListener = reloadListener`
  5. `SingBoxOptions.MyOptions`：补 `public CertificateOptions certificate;`（此前同样只在 `Options` 类、`MyOptions` 缺——与 endpoints 同坑）
  6. `ConfigBuilder.kt`：非空时下发顶层 `certificate { store:"system", certificate:[PEM] }` → 系统 CA + 自定义 CA 叠加信任。
  - CI run: https://github.com/rainsmen/nekoboxi/actions/runs/26707574348

> 运行期待验证：粘贴一张自签 CA → 用该 CA 签发的节点握手应成功（仍属「构建级」通过，行为未实测）。

---

**2026-05-31（续3）· 首次真机测试 → 发现并修复 1.13 运行时阻断（legacy inbound 字段）**

**现象**：旧节点连接报 `decode config: inbounds[1]: legacy inbound fields are deprecated in sing-box 1.11.0 and removed in sing-box 1.13.0`。**说明此 1.13 build 此前从未真正运行过**（仅构建级验证），首测即撞配置不兼容——正是评估文档预警的「ConfigBuilder 配置现代化」。

**根因**：`ConfigBuilder` 给 TUN + Mixed inbound 生成了 `sniff` / `sniff_override_destination` / `domain_strategy`，这一族（sing-box `option.InboundOptions`）在 1.13 已移除（`option/inbound.go:50` 检测到非空即拒；TUN 因非 listen inbound 未触发，故报在 Mixed=inbounds[1]）。

**修复**（commit `02f83c9`，CI 绿）：按官方 migration 把 inbound 字段迁移到 route rule actions：
- 删 TUN+Mixed 的 `sniff/sniff_override_destination/domain_strategy`。
- `route.rules` 开头注入：`needSniff` → `{action:"sniff"}`；`genDomainStrategy` 非空 → `{action:"resolve",strategy:...}`（sniff 前、resolve 后，匹配 TUN 数据流；位于 hijack-dns 之后、路由规则之前）。
- `SingBoxOptions.Rule_DefaultOptions` 补 `strategy` 字段。
- 删除已无用的 `needSniffOverride`（1.13 sniff action 无独立 override 开关，sniff 即隐式使用嗅探域名；trafficSniffing 的 1-vs-2 区分合并）。
- 路由规则裸 `outbound` 在 1.13 仍有效（`DefaultRule` 内嵌 `RuleAction`，action 默认 route）→ 现有分流规则无需改。
- CI run: https://github.com/rainsmen/nekoboxi/actions/runs/26708208244

> ⚠️ 仅构建级通过，**需真机复测**：①「decode config」错误消失、节点能连；②域名分流仍命中（依赖 sniff）；③`resolveDestination` 开启时 IP 分流正常（依赖 resolve）。

---

**2026-05-31（续4）· 第二个运行时阻断：netlink ban（libcore 未注册 adapter.PlatformInterface）+ sing-box 游离文件落定**

**现象**：启用节点报 `create service: initialize network manager: create network monitor: netlink socket in Android is banned by Google`。

**根因**（Go/libcore 层）：sing-box 1.13 的 `route.NewNetworkManager` 用 `nm.platformInterface != nil` 决定监视器来源——nil 就建 netlink 监视器（Android 禁）。`box.New` 从 ctx 取的是 **`adapter.PlatformInterface`**；而 libcore（box.go:92）只注册了 gomobile 那套 **`libbox.PlatformInterface`**，二者不同（中间需 `platformInterfaceWrapper` 桥接，未导出）。matsuri shim 1.13 移植时漏了这步。

**修复**（双仓库）：
- **sing-box**（`1.13.x-neko`）：新增导出 `libbox.RegisterPlatformInterface`（同包，可访问未导出的 `platformInterfaceWrapper`），包装并注册 `adapter.PlatformInterface`。
- **libcore/box.go**：`service.MustRegister[libbox.PlatformInterface]` → `libbox.RegisterPlatformInterface(ctx, …)`。

**sing-box 两个游离文件——本次定夺（均为 Go 1.25 构建必需）**：
- `go.mod`：cronet-go 对齐到 `20260413`。**理由**：libcore 是主模块（`replace sing-box=>本地`，sing-box 自身 go.sum 被忽略，只认 libcore go.sum）；libcore go.sum **只有 20260413、没有 20260513**。若 sing-box 留 20260513，MVS 选高版本 → libcore go.sum 缺条目 → 构建失败。已提交（CI 实测 cronet 正常下载）。
- `pidfd_android.go`：**最终删除**（非加 tag）。它用 `//go:linkname os.checkPidfdOnce` 给 Go<1.24 做 pidfd workaround，`//go:build !go1.24` 下在 Go 1.25 本就被排除；但 `gomobile-matsuri` 解析器在排除前读文件头，报 `multiple //go:build comments`。删除在 Go 1.25 下编译结果等价且绕开解析错。

**验证**：libcore 缓存因 box.go 改动失效 → **CI 首次从零跑完整 Go 构建（Go 1.25，约 10+ 分钟）成功** → Native Build ✅ + Build OSS APK ✅，产出 `APKs`(≈25 MB)。
- sing-box `1.13.x-neko`：`000c1f3d → b30642b(helper+游离文件) → 871d070(删 pidfd)`
- 主仓库：`02f83c9 → 95b0b1b(libcore box.go)`
- CI run: https://github.com/rainsmen/nekoboxi/actions/runs/26708704438

> ⚠️ 仍需真机复测：netlink 错误消失、VPN 能启动、TUN 流量正常。这是 Go 侧第一次真编译通过，原生层风险（gvisor/监视器/拨号）只能运行期验证。

---

**2026-05-31（续5）· 第三个运行时阻断（TUN 地址字段）+ 主动扫描全部 1.12/1.13 移除字段**

**现象**：`initialize inbound[0]: legacy tun address fields are deprecated in sing-box 1.10.0 and removed in sing-box 1.12.0`。

**修复**（commit `f2507b6`，CI 绿，纯 app 层缓存命中）：TUN 的 `inet4_address`/`inet6_address`（`protocol/tun/inbound.go:61` 检测非空即拒）→ 合并为 1.12 的 `address`（v4/v6 同一 Listable，按 ipv6Mode 取）。`SingBoxOptions.Inbound_TunOptions` 加 `address` 字段。

**主动扫描**（避免逐个撞）：grep sing-box 全部 `removed in sing-box` 硬拒绝，对照 ConfigBuilder 生成内容：
- ✅ **已排除**：ECH（只下发新格式 `enabled`/`config`，无 legacy 的 `pq_signature_schemes_enabled`/`dynamic_record_sizing_disabled`）；dns outbound（`protocol=dns` 是 hijack-dns 规则，非出站）；geoip/geosite 字段（用 rule_set）；tun gso；SSR/proxy-protocol（不生成）。
- ⚠️ **剩余 2 个雷（场景特定，非通用）**：
  - **A. direct inbound `override_address`/`override_port`**（ConfigBuilder:448-455）：仅 `needExternal`（外部插件代理）路径生成；普通节点不触发。1.13 direct 出站的 override 已移除（`option/direct.go:36`），此处是 direct **入站**端口映射，是否受影响待核（外部插件场景才需处理）。
  - **B. WireGuard outbound**：`WireGuardFmt` 生成 `type="wireguard"` 出站，但 `libcore/box_include.go:88` 的 `RegisterOutbound` 已注释、仅注册 `RegisterEndpoint`(96)（1.13 WG 改 endpoint 架构）→ **WG 节点必炸**，需把 `WireGuardFmt` 改为构建 endpoint 并进 `endpoints[]`（参照 Tailscale 模式）。仅影响 WG 用户。

> ⇒ 普通 vmess/trojan/vless 节点的通用阻断应已扫清（inbound sniff + tun address）。A/B 待对应节点类型测试时再处理。

---

**2026-05-31（续6）· 配置可加载但浏览不通 → FakeDNS 对 HTTPS-RR 查询报错（1.13 回归）**

**现象**：节点能启用、URLTest 有延迟，但绝大部分网页打不开（少数能开）。

**关键诊断**（用户 debug 日志）：URLTest 走 `CreateProxyHttpClient` 直连测速、不经 TUN，故延迟正常不代表数据通路通。日志显示 sniff/路由都正常（连接正确走到 `outbound/vless[proxy]`），真正的错误是：
```
dns: exchange s1.hdslb.com. IN HTTPS
dns: match[5] inbound=tun-in => route(dns-fake,disable-cache)
ERROR dns: exchange failed for ... IN HTTPS: only IP queries are supported by fakeip
ERROR router: process DNS packet: only IP queries are supported by fakeip
```
Chrome 对每个域名同时查 `A` 和 `HTTPS`(type 65，用于 HTTP/3+ECH)。`A` 走 fakeip 返回假 IP 成功；但 **sing-box 1.13 的 fakeip 对非 A/AAAA 查询返回错误**（旧版静默 NODATA），导致整个 DNS 响应被丢 → Chrome 卡死。

**根因定位**：`ConfigBuilder` 的 fakeip DNS 规则把 tun-in 的**所有**查询路由到 `dns-fake`，未限制 query_type。

**修复**（commit `7b20816`，CI 绿，纯 app 层）：给 fakeip 规则加 `query_type = listOf("A", "AAAA")`，HTTPS/其它类型落到 `dns.final_ = dns-remote`（真实解析）。

> 临时绕过（无需新包）：设置里关 FakeDNS 即可恢复（也验证了该诊断）。
> 这是「URLTest 正常但浏览不通」的经典案例：URLTest 不经 TUN/路由/DNS，只证明出站可达。

---

**2026-05-31（续7）· FakeDNS 默认关闭 + 策略：先确认基线再开原生 naive**

- **FakeDNS 默认关闭**（commit `1a21a13`，CI 绿，run 26710405543）：`DataStore.enableFakeDns` 默认 `true → false`。注意仅影响新装/未设过的用户；既有安装保留已存值，需手动关一次。
  - 关键事实：fakeip 的 `query_type` 修复写在 `if (useFakeDns)` 块内，**「fakeip 关」的代码路径与上一版逐字节相同** → 新包关 fakeip 必与上一版关 fakeip 表现一致；若新包关 fakeip 仍不通，则属另一个 DNS bug（需 fakeip-off debug 日志）。
- **策略决定（用户拍板）**：**先确认 1.13 基线**（用 `1a21a13` 关 fakeip 测普通节点浏览正常）**，再单独开分支做原生 naive**（Phase 3）。
  - 原生 naive 风险已明确告知：重启 `with_naive_outbound`+cronet = 重新引入 `R_AARCH64_PREL32`（历史 CI 杀手），可能打破当前首个绿色 Go 构建；将单独分支进行、备选回退外部插件。待基线确认后启动。

> 当前可用基线提交：主仓库 `1a21a13`（rainsmen/nekoboxi:main）/ sing-box `871d070`（rainsmen/singbox:1.13.x-neko）。

---

**2026-05-31（续8）· 路由根因：1.13 改了空 `route.final` 的兜底行为（→ direct）**

**现象**：关 FakeDNS 后仍打不开网页；用户发现在路由设置里**启用「中国IP规则」即恢复正常**（此时 FakeDNS 开也能用）。

**根因（sing-box 源码实锤）**：`adapter/outbound/manager.go:58-77`——`route.final`(defaultTag) 为空时，1.13 **不再用第一个 outbound 作默认**，而是 `defaultOutboundFallback()` **新建一个 DIRECT 出站**作兜底。NekoBox 从不设 `route.final`，依赖旧版「第一个=默认(proxy)」→ 1.13 下**未匹配流量全走 direct**。用户在墙内：境外站直连被墙打不开（绝大多数），国内站能开；启用带兜底/导向 proxy 的「中国IP规则」才救活。日志 `outbound/vless[proxy]` 也佐证 proxy 出站 tag=`proxy`。

**修复**（commit `8f73ed4`，CI 绿，run 26710924428）：route 块显式 `final_ = TAG_PROXY`，恢复 NekoBox 一贯的 proxy 默认。所有分支（selector/单节点/forTest）均存在 `proxy` tag 出站，安全。

> 这是「URLTest 正常但浏览不通」的第二层根因（第一层是 fakeip-HTTPS）。至此普通节点应可正常浏览（proxy 默认 + 各 legacy 字段已迁移 + fakeip 修复）。

**Naive 方案评估（用户拍板：打包外部插件，非原生 cronet）**：参考用户旧仓库 rainsmen/nekobox 的 `download_naive.sh`——构建时从 MatsuriDayo/plugins 拉预编译 naive 插件 APK、抽 `libnaive.so` 放进 `app/src/main/jniLibs/<arch>/`，打进主包，插件作子进程跑 SOCKS。**优于原生 cronet**：零构建风险（不碰 R_AARCH64_PREL32、不威胁绿色构建）、工作量低、已被验证。

---

**2026-05-31（续9）· Naive 打包外部插件 ✅ 实现并构建通过（Phase 3 以「外部插件」方案落地）**

完全照搬 hysteria 的「内置 exe 插件」模式（仅 arm64-v8a，commit `ce9a9c5`，CI 绿，run 26711252542，APK 25→27 MB 即含 libnaive.so）。关键机制：bundled .so 时 `Plugins.isUsingMatsuriExe` 返回 false → `needExternal` 保持 true → 走 ConfigBuilder 的 mapping 路径（与 hysteria 一致）。改动 6 处（`download_naive.sh` 已存在，仅接入）：
1. `preview.yml`：Gradle Build 前加 `bash download_naive.sh`（抽 libnaive.so 进 jniLibs）。
2. `PluginManager.kt`：`"naive-plugin" -> soIfExist("libnaive.so")`。
3. `ProxyEntity.needExternal()`：加 `TYPE_NAIVE -> true`。
4. `ConfigBuilder` plugin-id switch：加 `is NaiveBean -> "naive-plugin"`。
5. `NaiveFmt.buildNaiveConfig(port)`：生成 naive JSON（`listen socks://127.0.0.1:port` / `proxy=toUri(true)` / `host-resolver-rules` 保留真实 SNI）。
6. `BoxInstance`：两个循环加 NaiveBean 分支——`initPlugin("naive-plugin")`+`buildNaiveConfig`，写配置文件后 `processes.start([libnaive.so, configFile])`。

> ⚠️ 改 `preview.yml` 使 libcore 缓存键失效 → 本次 CI 完整重编 Go（再次验证 Go 侧整链）。原生 `buildSingBoxOutboundNaiveBean`（`type:"naive"`）成为死代码（naive 现恒走外部插件），保留无害。
> **待真机验证**：① route.final 基线（普通节点浏览正常）；② naive 节点能连、能上网（插件子进程起来、SOCKS 端口映射通）。
