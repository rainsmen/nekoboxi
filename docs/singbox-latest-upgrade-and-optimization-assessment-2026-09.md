# sing-box 最新稳定版（v1.14.0）升级与项目优化改进评估（2026-09）

> **评估日期**：2026-09-03  
> **评估对象**：ThBox for Android（`rainsmen/nekoboxi`），当前分支 `upgrade/singbox-1.13.14-naive-plugin`  
> **项目基线**：Android 客户端 + `libcore`（Go 1.25 + gomobile-matsuri）+ `sing-box 1.13.14-neko`（官方 `v1.13.14` + 本地定制补丁）  
> **上游状态**：官方最新稳定发布版 **`v1.14.0`**（Sun Aug 30 17:57:55 2026，提交 `0b8995879`，并在 `upstream/stable` 跟踪维护）  
> **前序文档**：承接 [`docs/singbox-latest-upgrade-assessment-2026-08.md`](file:///home/rainan/projects/nekobox/docs/singbox-latest-upgrade-assessment-2026-08.md)、[`docs/swirling-fluttering-fiddle.md`](file:///home/rainan/projects/nekobox/docs/swirling-fluttering-fiddle.md) 与 [`docs/shadowrocket-advanced-rules-feasibility.md`](file:///home/rainan/projects/nekobox/docs/shadowrocket-advanced-rules-feasibility.md)

---

## 0. 摘要与核心结论

1. **官方 1.14.0 正式稳定版已发布**：在上月（2026-08-20）评估时，1.14 线仍处于 `beta.17`，建议先升 1.13.19；而截至 2026 年 8 月底，官方已正式发布 **`v1.14.0` 稳定大版本**。
2. **核心收益极高**：1.14.0 带来了数项直击移动端核心痛点的新特性，包括：
   - **`package_name_regex`**：内核级原生包名与正则路由/DNS，彻底解绑 Android 侧死锁卡顿的静态 UID（`PackageCache`）映射；
   - **乐观 DNS 缓存 (`dns.optimistic`) + `store_dns`**：零延迟返回过期记录并在后台更新，配合数据库持久化，彻底解决弱网和冷启动 DNS 迟钝；
   - **并行 DNS 竞速 (`race` / `evaluate`)**：消除未知域名 fallback 延迟与 DNS 污染；
   - **Hysteria 2 Chrome QUIC 拟态与 gecko 混淆**：抗指纹识别与抗封锁能力显著提升；
   - **原生 Snell 协议**：无需闭源插件即可支持 Snell v1~v4。
3. **升级 1.14.0 存在硬性破坏性阻断（Hard Breaking Change）**：
   - **旧版 DNS 格式被彻底抹除**：1.14.0 强行移除了遗留的 `address: "local"`、`address: "rcode://..."` 以及 `dns.fakeip: {...}` 配置。如果不重构 [`ConfigBuilder.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt) 的 DNS 生成器为 **Typed DNS Server** 格式，应用启动将直接抛错崩溃。
4. **项目还可进行的系统性改进**：
   - **分流规则与规则集体系**：放宽 [`AssetsActivity.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/ui/AssetsActivity.kt) 的 `.db` 导入限制以支持 `.srs`，并引入远程规则集订阅与定时自动更新；
   - **DNS 与缓存**：常态化启用 `cache_file`（当前仅测试和 Clash API 才启用，日常 VPN 竟然为 null）；
   - **Naive 外部插件打包**：[`download_naive.sh`](file:///home/rainan/projects/nekobox/download_naive.sh) 目前仅打 `arm64-v8a` 单架构，需补齐 `armeabi-v7a` 和 `x86_64`；
   - **客户端架构与性能**：消除 [`PackageCache.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/utils/PackageCache.kt) 中 `runBlocking` 引发的主线程卡顿，清理广泛滥用的 `GlobalScope.launch` 内存泄漏风险，升级带 CVE 漏洞的老旧依赖（如 `snakeyaml:1.30`）。

---

## 一、可引入最新版 sing-box（v1.14.0）的新特性评估

根据特性对 Android 移动客户端（ThBox）的契合度与价值进行分级：

### 1. 第一梯队：强相关高价值（直接突破移动端体验瓶颈）

#### ① `package_name_regex` 及内核原生包名路由
- **现状与痛点**：当前 ThBox 在 [`ConfigBuilder.kt:536-552`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt#L536-L552) 中，通过 [`PackageCache.awaitLoadSync()`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/utils/PackageCache.kt#L70) 将包名转成静态 UID 列表传递给规则的 `user_id`。
  - **严重缺陷**：无法应对应用双开/工作分身（Work Profile，UID 不同）；应用重装/更新后 UID 改变会导致分流规则失效；且无法使用正则（如只想匹配 `^com\.google\.` 或 `^org\.telegram\.` 必须手动勾选几十个应用）。
- **1.14 新能力**：在路由规则、DNS 规则及 Headless 规则项中原生支持 `package_name` 与 `package_name_regex`。
- **价值**：分流直接交由底层内核（通过 Linux procfs / netlink 读取 socket 归属），天然兼容多开分身与动态 UID，并支持灵活的正规表达式批量分流，大幅精简 Android 侧代码并杜绝失效。

#### ② 乐观 DNS 缓存 (`dns.optimistic`) 与持久化 (`cache_file.store_dns`)
- **现状与痛点**：移动设备频繁切换 Wi-Fi 与蜂窝基站，网络握手环境脆弱。DNS 缓存过期后重新解析往往需要 100~500ms，导致冷启动加载首屏时出现可感知的停顿。且当前 ThBox 在正常 VPN 运行时没有开启 `cache_file`，每次重连 DNS 缓存全失。
- **1.14 新能力**：`dns.optimistic` 允许对已过期的 DNS 记录立即可用（以 0ms 延迟先返回旧 IP 建立连接），同时在后台静默发起异步网络刷新。配合 `cache_file.store_dns`，DNS 缓存直接持久化到本地 SQLite。
- **价值**：彻底消除重复访问域名的首包解析延迟，重连或冷启动秒开，移动端网络体验提升极其明显。

#### ③ 并行 DNS 竞速与响应评估 (`race` / `evaluate` / `match_response`)
- **1.14 新能力**：引入 Response Match 体系与并行竞速机制。
  - `evaluate`：预先查询某组 DNS，将响应暂存为 tagged 结果；
  - `race`：使多个 DNS 解析并行竞速，谁先返回非空/非污染的有效响应即采纳，其余自动中止并取消；
  - `match_response`：根据 DNS 响应的 RCode、Answer 内容精细化分流。
- **价值**：告别“先匹配规则 -> 规则未命中走 fallback -> 超时卡死”的传统串行 DNS 机制，大幅缩短移动网络下的高延迟 DNS 查询时间。

#### ④ TUN DNS 模式与平台原生接管 (`dns_mode: "hijack"`)
- **现状与痛点**：1.13 版本曾多次遭遇 TUN 回环、FakeDNS 回环与 DNS 漏水问题，[`ConfigBuilder.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt) 中堆积了大量手动劫持 53 端口与防环路的迂回逻辑。
- **1.14 新能力**：TUN 入站新增 `dns_mode` 和 `dns_address`，默认 `hijack` 模式由 sing-box 内核直接规范接管平台 DNS，从底层杜绝路由环路。
- **价值**：大幅简化 Android 侧配置生成代码，提升 VPN 稳定性。

#### ⑤ Hysteria 2 全面抗封锁强化
- **Chrome QUIC 指纹拟态**：默认伪装 Chrome QUIC ClientHello 握手特征，破坏防火墙针对标准 Hysteria2 协议的特征识别（注意：需保留 `disable_chrome_parrot` 开关以避免服务端 Ed25519 证书握手失败）。
- **新增 `gecko` 混淆**：与原有的 `salamander` 并列，支持自定义 `min_packet_size` 与 `max_packet_size`。
- **BBR Profile 与端口跳跃随机化**：支持 `bbr_profile` 与 `hop_interval_max`，提升弱网抗封锁与抗限速表现。

---

### 2. 第二梯队：中高价值（丰富生态、规则扩展与编辑体验）

| 特性 | 说明 | 落地价值 |
|---|---|---|
| **规则集多 Tag (`tags`) 与冷启动预置 (`initial_path`)** | 单规则集支持多个 tag 联合声明；`initial_path` 为远程规则集提供本地离线回退文件。 | 解决手机首次冷启动或无网初始化时，远程规则集未下载完成导致的路由断流或阻塞。 |
| **JSON Schema 智能提示与配置校验** | sing-box 1.14 提供官方 JSON Schema（`$schema` 与 `sing-box schema` 命令）。 | 与应用内内置的代码编辑器（[`editorkit`](file:///home/rainan/projects/nekobox/app/build.gradle.kts#L64)）结合，为高级自定义配置提供字段实时补全与格式校验，降低手写 JSON 出错率。 |
| **Snell 协议原生支持** | 官方采用纯 Go 实现 Snell 协议（v1~v4，支持多路复用与完整 TCP 语义），不依赖闭源 core。 | 补全对常见商业订阅节点格式的支持，可新增 `SnellBean` 与编辑面板。 |
| **Tailscale 深度集成** | 支持 Taildrop 局域文件快传、Tailscale SSH Server、`listen_port` 监听端口。 | 丰富 Tailscale endpoint 能力，打造移动端组网与文件跨端互传体验。 |
| **局域网/热点共享分流** | `source_mac_address`、`source_hostname`、`include/exclude_mac_address`、`mDNS` 解析。 | 当手机开启移动热点或“允许局域网连接”时，可对连接热点的不同子设备实施独立分流规则。 |
| **TLS Spoofing (`tls_spoof`)** | 发送携带白名单 SNI 的伪造 ClientHello，欺骗基于 SNI 重置的审查设备。 | 抗审查增强项（需确认 Android VPN 环境下的 raw socket 权限适配）。 |
| **AnyTLS 客户端元数据清空** | 1.13.16+ 默认清空无用的客户端特征元数据。 | 防止部分商业节点服务商基于特异性元数据对客户端打标签与画像。 |

---

### 3. 第三梯队：特定场景或可跳过特性

- **OpenVPN / OpenConnect 客户端 endpoint**：1.14 原生内置了 OpenVPN 与 Cisco AnyConnect / GlobalProtect / Fortinet 客户端。对于有多 VPN 统一管理需求的用户有价值，但改动涉及交互式认证（Interactive Auth），按需引入。
- **Bridge Outbound (L3 直连出站)**：免去 L3->L4 翻译开销，但仅支持 Rooted Android 设备。
- **建议跳过的服务端/桌面端特性**：Linux 命名空间 (`netns`)、Windows Schannel / Apple Network.framework 引擎、USB/IP 服务等，与 Android 架构无关。

---

## 二、升级至 1.14.0 的硬性破坏性变更与迁移成本

如果决定跟进 1.14.0，必须清醒评估以下**直接导致编译或运行时崩溃**的代码修改点：

### 1. 致命阻断：旧版 DNS 服务器与 FakeIP 格式彻底废除
- **官方变更**：1.14.0 彻底删除了旧版 DNS 格式，不再兼容 `address: "local"`、`address: "rcode://success"`、`address: "tls://..."` 以及 `dns.fakeip: {...}`。遇到遗留格式直接报错退出：
  ```text
  legacy DNS fakeip options are deprecated in sing-box 1.12.0 and removed in sing-box 1.14.0
  legacy DNS server formats are deprecated in sing-box 1.12.0 and removed in sing-box 1.14.0
  ```
- **代码重构点**：[`ConfigBuilder.kt:694-780`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt#L694-L780) **必须全面迁移为 Typed DNS Server**：
  ```kotlin
  // 必须改用显式 type 的写法：
  dns.servers.add(DNSServerOptions().apply {
      type = "local"
      tag = "dns-local"
      detour = TAG_DIRECT
  })
  dns.servers.add(DNSServerOptions().apply {
      type = "rcode"
      tag = "dns-block"
      rcode = "success"
  })
  if (useFakeDns) {
      dns.servers.add(DNSServerOptions().apply {
          type = "fakeip"
          tag = "dns-fake"
          inet4_range = "198.18.0.0/15"
          inet6_range = "fc00::/18"
      })
  }
  ```

### 2. DNS 规则与缓存参数弃用
- `dns.independent_cache` 被官方移除（底层自动按 transport 键值隔离），配置中无需再声明；
- DNS 规则中的 `ip_cidr` / `ip_is_private` 必须声明 `match_response: true`，否则被判定为非法配置。

### 3. 本地补丁与工具链 Rebase 成本
当前 `sing-box` 子模块维护了 5 项关键改动，升级 1.14.0 需逐项 Rebase：
1. **Naive 连接预热** (`feat(naive): add connection warmup`)；
2. **Android `adapter.PlatformInterface` 注册** (`b30642b5`，绕过 Android seccomp 屏蔽的 netlink 监听)；
3. **移除 `pidfd_android.go`** (`871d070d`，消除 Go 1.25 下 gomobile 报错)；
4. **`dialer.DoNotSelectInterface`** 与 **VLESS mux flow 清空**；
5. **[`libcore/box_include.go`](file:///home/rainan/projects/nekobox/libcore/box_include.go)** 的显式注册表：需对齐 1.14 的新模块导出（注册 Snell、Bridge，同步 Endpoint 与 Service 结构）。

---

## 三、该项目还可以进行的系统性改进与优化

结合代码深挖、文档审查与架构分析，本项目在以下六大维度存在明确的改进空间：

### 1. 分流规则与规则集体系的彻底重构（核心痛点）

- **开放远程规则集（`.srs` / URL 订阅）**：
  - **现状**：UI 仅支持本地 `geosite.db` / `geoip.db` 分类；[`AssetsActivity.kt:107`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/ui/AssetsActivity.kt#L107) 硬编码限制只能导入 `.db`，不支持 `.srs`（二进制规则集）；无法在 UI 配置远程规则集 URL。
  - **改进**：
    1. 扩展 [`RuleEntity`](file:///home/rainan/projects/nekobox/docs/shadowrocket-advanced-rules-feasibility.md#L29)，允许直接配置远程规则集 URL（支持主流 Loyalsoldier, ACL4SSR, Sukka 等规则订阅）；
    2. 修改 [`AssetsActivity.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/ui/AssetsActivity.kt)，放宽后缀校验，支持本地导入 `.srs` 与 `.json` 规则集文件；
    3. 支持 Shadowrocket / Clash 规则集的一键格式转换与挂载。
- **引入规则集自动更新机制**：
  - **现状**：只有节点订阅具备 [`GroupUpdater.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt) 定时更新，分流规则与 Geo 资源完全无法自动同步。
  - **改进**：通过 Android `WorkManager` 增加规则集与 GeoIP/GeoSite 文件的定时后台更新任务。

### 2. DNS 与持久化缓存系统治理

- **日常运行常态化启用 `cache_file`**：
  - **现状**：[`ConfigBuilder.kt:164-178`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt#L164-L178) 中，**仅在测速 (`forTest`) 或开启 Clash API 时才生成 `cache_file`**，正常 VPN 运行时 `cache_file = null`！
  - **影响**：远程规则集无法持久化缓存（每次断线重连/重启都需重新下载）；DNS 缓存随进程重启全部丢失。
  - **改进**：常规运行时常态化开启本地 `cache.db`，并配合 1.14 开启 `store_dns: true` 和 `store_fakeip: true`，大幅减少重连 DNS 解析耗时与流量消耗。
- **FakeDNS 健壮性修复**：
  - 规范 FakeDNS 缓存生命周期，配合 1.14 typed fakeip，严格限制 FakeIP 仅响应 A/AAAA，避免阻断 Chrome HTTPS-RR (type 65) 与 ECH 探测，使 FakeDNS 达到日用级别稳定。

### 3. NaiveProxy 与外部插件链路治理

- **补齐多 ABI 架构打包**：
  - **现状**：[`download_naive.sh:11`](file:///home/rainan/projects/nekobox/download_naive.sh#L11) 硬编码只下载解压 `arm64-v8a` 的 `libnaive.so`。若用户在 32 位老设备、x86_64 模拟器或 Chromebook 上使用，调用 Naive 会直接因缺失动态库而崩溃。
  - **改进**：在下载脚本和打包流程中支持 `armeabi-v7a` 与 `x86_64` 多 ABI 支持，或在 Release 时拆分多架构 APK。
- **外部插件进程池（`GuardedProcessPool`）健壮性**：
  - 增强子进程退出状态监听与异常重启机制，优化 Android Doze 休眠唤醒时的端口恢复，避免残留僵尸进程占用本地 mapping 端口。

### 4. Android 客户端性能优化与技术债清理

- **消除主线程 `runBlocking` 与 `PackageCache` 耗时**：
  - **现状**：[`PackageCache.kt:70`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/utils/PackageCache.kt#L70) 的 `awaitLoadSync()` 采用 `runBlocking` 同步等待包名加载，且 `PackageManager.getInstalledPackages()` 为重度系统 Binder IPC。应用多的设备上，打开规则设置或配置重载会引起明显掉帧。
  - **改进**：改用 Kotlin Coroutine + Flow 异步惰性加载；迁移到内核级 `package_name_regex`，直接剔除 Android 侧复杂的包名-UID 同步计算。
- **消灭 `GlobalScope` 滥用与协程泄漏**：
  - **现状**：[`Asyncs.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/ktx/Asyncs.kt#L14)、[`GroupUpdater.kt:60`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt#L60) 等多处直接使用 `GlobalScope.launch`，脱离结构化并发体系，Activity/Service 销毁时无法取消任务。
  - **改进**：规范定义应用生命周期范围内的 `AppScope`、`ServiceScope`，统一异常捕获与任务取消。
- **老旧依赖库与已知 CVE 安全修复**：
  - [`app/build.gradle.kts`](file:///home/rainan/projects/nekobox/app/build.gradle.kts#L68-L70)：
    - `snakeyaml:1.30`（存在已公开反序列化 CVE 漏洞）升级至 `2.x`；
    - `okhttp:5.0.0-alpha.3`（多年未更新的 alpha 早期版本）建议对齐稳定版 `4.12.0`；
    - 升级 `Material Components`（`1.8.0 -> 1.12.0+`），适配 Android 15 (Target SDK 35) 的预测性返回（Predictive Back）与 Edge-to-Edge 边到边规范。

### 5. Web 控制台与管理服务现代化

- **接入 sing-box 1.14 官方 API Service 与现代 Dashboard**：
  - **现状**：内置写死在 `../files/yacd` 的旧版 Yacd 静态资源（[`ConfigBuilder.kt:174`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt#L174)）。
  - **改进**：启用 sing-box 1.14 官方 gRPC `API service`，替换或升级为现代化 Web 控制台（如 `sing-box-dashboard` 或 `metacubexd`），提供更详尽的实时连接诊断、DNS 解析链路图表、STUN 检测和流量曲线。

### 6. 日常交互与功能体验增强（QoL）

- **节点与订阅管理**：
  - 增加订阅节点**批量正则重命名**与**自动去重**；
  - 测速增加并发数上限控制与超时熔断，防止瞬时并发打满导致系统 VPN 断流。
- **系统级集成增强**：
  - 状态栏 Quick Settings Tile：支持点击切换开关，长按弹出 BottomSheet 快速切换节点；
  - 桌面小部件（Widget）：实时显示当前连接节点、上下行网速与运行时长。

---

## 四、分阶段实施路线图

```mermaid
graph TD
    A[第一阶段：低风险前期治理] --> B[第二阶段：ConfigBuilder DNS 重构]
    B --> C[第三阶段：升级 sing-box 1.14.0]
    C --> D[第四阶段：特性释放与体验重塑]

    subgraph 第一阶段：架构与安全治理
    A1[放宽 AssetsActivity 导入 .srs 限制]
    A2[download_naive.sh 补齐多 ABI]
    A3[依赖升级: snakeyaml 2.x & okhttp 4.12]
    A4[常态化开启 cache_file 数据库]
    end

    subgraph 第二阶段：DNS 现代化改造
    B1[重构 ConfigBuilder 生成 Typed DNS Server]
    B2[清理废弃的 independent_cache 与 fakeip 语法]
    B3[整理本地 5 个 patch 准备 rebase 1.14.0]
    end

    subgraph 第三阶段：核心升级与构建调优
    C1[sing-box 子模块升级至 v1.14.0]
    C2[libcore/box_include.go 同步 1.14 注册表]
    C3[验证 TUN hijack DNS 模式与 Chrome QUIC 拟态]
    C4[GitHub Actions 跑通完整 CI 编译]
    end

    subgraph 第四阶段：功能落地与 QoL
    D1[接入 package_name_regex 规则支持]
    D2[接入乐观 DNS 缓存与并行竞速 DNS]
    D3[支持远程 .srs 规则集订阅与定时更新]
    D4[接入 Snell 协议与现代 Web Dashboard]
    end
```

| 阶段 | 重点任务 | 风险与验收标准 |
|---|---|---|
| **Phase 1：低风险前期治理** | 修复 `snakeyaml` 漏洞、放宽 `.srs` 导入限制、补齐 Naive 多架构打包、常态化启用 `cache_file` | **低风险**。在现有 `1.13.14-neko` 上即可完成，不触碰核心，改善日常缓存与打包兼容性。 |
| **Phase 2：DNS 现代化改造** | 将 [`ConfigBuilder.kt`](file:///home/rainan/projects/nekobox/app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt) 的 DNS 逻辑彻底重构成 Typed 格式（`local`, `rcode`, `fakeip`, `tcp`, `udp`, `tls` 等） | **中风险**。新旧格式兼容性测试，保证 1.13 和 1.14 均可顺利解析。这是上 1.14 的唯一硬性前提。 |
| **Phase 3：核心升级与 CI 达绿** | `sing-box` 切换至官方 `v1.14.0`，Rebase 本地补丁，更新 [`libcore/box_include.go`](file:///home/rainan/projects/nekobox/libcore/box_include.go)，构建 `libcore.aar` | **中-高风险**。需验证 Android VPN 启动、TUN hijack DNS 接管、代理出站与真机运行稳定性。 |
| **Phase 4：特性释放与体验升级** | 接入 `package_name_regex`、乐观 DNS 缓存、Snell 协议与远程规则集定时自动更新 | **收益兑现期**。彻底释放 1.14.0 核心能力，大幅提升 Android 客户端的使用体验。 |
