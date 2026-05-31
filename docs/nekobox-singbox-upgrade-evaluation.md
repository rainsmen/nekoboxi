# NekoBoxForAndroid 升级 sing-box 新特性 · 可行性评估

> **评估日期**：2026-05-31
> **评估对象**：基于真实上游仓库，而非本地工作目录（本地目录为其它 AI 工具改动后的产物，且 GitHub Actions 编译不通过，不作为依据）
> **数据来源**：
> - `MatsuriDayo/NekoBoxForAndroid`（App 本体，最新 release v1.4.2 / 2026-02-09）
> - `MatsuriDayo/sing-box` 的 `1.12.x` 分支（= 版本 `1.12.19-neko-1`，作者魔改版）
> - `SagerNet/sing-box` `v1.12.19`（官方对应基线）+ 官方 Changelog / Deprecated 文档
> **方法**：浅克隆三方仓库到干净目录，对魔改版与官方逐文件 `diff`，并审查 NB4A 的 libcore 构建脚本与 go.mod。

---

## 0. TL;DR（一句话结论）

经真实 diff 证实，作者的 sing-box **并没有"很多自定义变量"**——其配置 schema（`option/` 包）与官方**逐字节完全相同**，魔改仅为约 **250 行桥接包 + 7 个各几行的小 patch**。因此"fork 官方导致配置不兼容"的担忧**基本不成立**。升级的真正成本集中在 **`sing` 基础库升级 + 定制 `gomobile` 工具链 + libcore 架构适配**；而上一次 AI 尝试 CI 失败的根因是**额外捆绑了 cronet 原生 NaiveProxy**（原生链接错误 `R_AARCH64_PREL32`），与 sing-box 升级本身无关，应拆开处理。

---

## 1. 核心结论：先纠正关键前提

最初的预设是"作者的 sing-box 有很多自定义变量，直接 fork 官方会用不了"。但把 **`MatsuriDayo/sing-box@1.12.19-neko-1`** 与 **官方 `SagerNet/sing-box v1.12.19`** 逐文件比对后发现：

> **`option/` 包（即所有配置字段 / "变量"的定义）与官方逐字节完全相同，没有任何自定义字段。**

NekoBox 生成的 sing-box JSON 配置 schema 与官方 100% 一致。作者的魔改完全**不在配置变量层面**，而是一层很薄的**行为补丁 + Android 桥接代码**。

---

## 2. 证据：作者魔改的真实全貌

`1.12.19-neko-1` 相对官方 `v1.12.19` 的**全部**差异如下。

### ① 新增的辅助包 / 文件（共约 250 行）

| 内容 | 行数 | 作用 |
|---|---|---|
| `boxapi/`（4 个文件） | 232 | V2Ray 流量统计 API、代理拨号器（DialContext）、测速 HTTP client |
| `nekoutils/`（2 个文件） | 10 | 选择器回调变量 + SRS（geoip/geosite）规则加载接口 |
| `protocol/tun/fix_gvisor.go` + `fix_gvisor_stub.go` | ~30 | gvisor TUN 关闭修复 |

> 注：`nekoutils` / `boxapi` 中出现的 `option.HeadlessRule`、`option.V2RayStatsServiceOptions` 等均为**官方已有类型**，不是新增的配置变量。

### ② 对官方源码的 patch（7 个文件，均为几行的小改）

| 文件 | 改动 | 性质 |
|---|---|---|
| `box.go` | `PlatformLogWriter` 存在时强制启用 cache file / clash API（2 行） | Android 平台集成 |
| `common/dialer/default.go` | 新增全局开关 `var DoNotSelectInterface`，跳过接口选择（3 行） | VPN 接口由 Android 接管 |
| `protocol/group/selector.go` | 选中出站时调用 `nekoutils.Selector_OnProxySelected` 回调（3 行） | 通知 UI 更新 |
| `protocol/tun/inbound.go` | 关闭时调用 gvisor 修复 + import 别名（2 行） | 稳定性 |
| `protocol/vless/outbound.go` | 启用 multiplex 时清空 `flow`（4 行） | 兼容性修复 |
| `route/rule/rule_set_local.go` | 支持 `geoip:` / `geosite:` 路径前缀加载内置规则（22 行） | 内置分流规则 |
| `constant/version.go` | 版本号字符串 | — |

**全部改动到此为止。** 没有自定义协议、没有自定义 option 字段、没有改动配置结构。

---

## 3. 对"能否升级 / fork 官方"的含义

"自定义变量不兼容"问题**几乎不存在**。升级到新版官方 sing-box，真正要做的是：

- 把上述 ~250 行辅助包 + ~30 行 patch **重新 apply** 到新版本；
- Kotlin 侧的 `SingBoxOptions.java` / `ConfigBuilder.kt` 生成的是**标准官方配置**，无需为"作者私有字段"做任何适配。

**结论**：迁移的"配置兼容"维度基本是零成本的——这是个好消息。

---

## 4. 真正的升级难点（按难度排序）

| 难点 | 说明 | 难度 |
|---|---|---|
| **`sing` 基础库升级** | NB4A 当前 `sing v0.7.18`；sing-box 1.13 需 `v0.8.x`。bufio / dialer 等 API 变动会波及 `boxapi` 与 libcore | 🟡 中 |
| **定制 `gomobile-matsuri` 工具链** | NB4A 用 `MatsuriDayo/gomobile`（分支 `master2`）的魔改 gobind；1.13 要求 **Go 1.24+**，gomobile fork 需跟着 rebase 到新 Go，否则 bind 失败 | 🔴 中-高（工具链最脆弱） |
| **libcore 适配 1.13 新架构** | DNS transport 注册表 / endpoint 注册表（WireGuard 改为 endpoint）/ service 注册表三套重构 | 🟡 中 |
| **重新 apply 7 个 patch + 2 个包** | 机械工作，但 patch 落点（selector / vless / tun）在新版可能位移 | 🟢 低-中 |
| **`ConfigBuilder.kt` 配置格式现代化**（仅上 1.14 才强制） | DNS 改 typed 格式、TUN `inet4_address`→`address`、direct `override_address`→rule action。**属官方弃用路线，非作者私货** | 🟡 中-高 |

---

## 5. 诊断：上一次 AI 尝试为何 CI 编译不通过

对比真实上游可直接定位问题根因（供避坑）：

1. **上游 NB4A 的 libcore 根本不内置 NaiveProxy。** 真实 `libcore/build.sh` 的构建标签仅为：
   ```
   with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api
   ```
   **没有 `with_naive_outbound`，也没有 `with_tailscale`**。NekoBox 的 naive 走**外部插件**（独立 `.so` / `plugin.apk`）实现，不编进核心。
2. 那次改动同时做了两件高风险的事：**跳到 1.13.x** + **把 cronet-go 原生 naive 编进 libcore**。后者引入的 `R_AARCH64_PREL32`（relocation 315）**原生链接错误**，正是本地仓库最近多个 commit 一直在搏斗的根源——**这是 cronet / NDK 的原生链接问题，与 sing-box 升级无关**。

> **建议拆分关注点**：①sing-box 版本升级（可行、低-中风险）与 ②原生 naive 集成（独立、高风险、CI 失败的真正来源）**不要捆在一起做**。

---

## 6. 升级后 NekoBox 能新增哪些功能（基线 = 1.12.19）

| 官方新特性（1.13 / 1.14） | 可转化的 NekoBox 功能 | Android 适用 | 难度 |
|---|---|---|---|
| **TLS Fragment**（1.14） | 抗审查 TLS 握手分片开关 | ✅ | 🟢 低（route 选项 + UI） |
| **证书管理**（信任 CA 列表 / 修复 Android 证书读取） | 自定义 CA、修系统证书 | ✅ | 🟢 低-中 |
| **ICMP / ping over TUN** | 隧道内 ping、`network=icmp` 分流 | ✅ | 🟡 中 |
| **新 typed DNS + `domain_resolver`** | DNS 体系（1.14 强制迁移旧格式） | ✅ | 🔴 高（改 ConfigBuilder） |
| **新路由规则项**（interface IP / Tailscale·WG 路由） | 更精细分流 | ✅ | 🟡 中（主要是 UI） |
| **WireGuard endpoint 架构**（1.13） | WG 作为 endpoint，GSO 自动启用 | ✅ | 🟡 中（libcore 本就需改） |
| **Tailscale**（system interface / advertise tags / relay server） | 完整 Tailscale 支持 | ✅ | 🟡 中（上游本来未编入） |
| **原生 NaiveProxy**（QUIC / ECH / BBR 拥塞控制） | 内置 naive 替代外部插件 | ✅ | 🔴 高（cronet 原生链接，CI 杀手） |
| `kernel_tx/rx` TLS 卸载、`auto_redirect bypass`、MPTCP 处理 | — | ❌ **仅 Linux** | N/A |

> ⚠️ **理性筛选**：1.13/1.14 不少亮点（内核 TLS 卸载、auto_redirect bypass、MPTCP）**是 Linux 专属，Android 端用不上**。对 NekoBox 真正有价值的增量是：**TLS 分片、证书管理、ICMP、新 DNS/路由、Tailscale**。

---

## 7. 建议路线

1. **第一步——纯版本升级**：把上游 `1.12.19-neko` 的薄层（2 包 + 7 patch）rebase 到官方 **1.13.x**，先不碰 naive / tailscale。重点搞定 `sing` 库升级 + `gomobile-matsuri` 对 Go 1.24 的适配。配置层零改动即可运行。
2. **第二步——低成本特性**：ECH 开关、TLS Fragment、证书管理——改动小、价值高。
3. **第三步——决定是否上 1.14**：若上，则必须做 `ConfigBuilder.kt` 的 DNS / TUN / 路由格式现代化（唯一硬骨头，但属官方标准迁移路径）。
4. **原生 naive / cronet 单独立项**：不要与版本升级耦合——它才是会让 CI 反复挂掉的部分。

---

## 附录 A：关键事实速查

| 项 | 值 |
|---|---|
| NB4A 最新版本 | v1.4.2（2026-02-09） |
| 魔改 sing-box 版本 | `1.12.19-neko-1`（基于官方 `v1.12.19`） |
| NB4A 依赖（go.mod） | `sing v0.7.18`、`quic-go v0.52.0-sing-box-mod.3` |
| go.mod replace | `sing-box => ../../sing-box`、`libneko => ../../libneko` |
| 构建工具 | `gomobile-matsuri` / `gobind-matsuri`（`MatsuriDayo/gomobile` 分支 `master2`） |
| 上游构建标签 | `with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api` |
| 魔改对官方的改动总量 | 新增 2 包（~242 行）+ 2 个 tun 文件 + 7 文件小 patch；`option/` 包**零改动** |

## 附录 B：复现方法（分析所用命令）

```bash
# 1) 浅克隆三方仓库到干净目录
git clone --depth 1 -b 1.12.x https://github.com/MatsuriDayo/sing-box        matsuri-singbox
git clone --depth 1            https://github.com/MatsuriDayo/NekoBoxForAndroid nb4a
git clone --depth 1 -b v1.12.19 https://github.com/SagerNet/sing-box           official-singbox

# 2) 确认魔改版基线版本
grep 'Version =' matsuri-singbox/constant/version.go   # => 1.12.19-neko-1

# 3) 找出仅存在于魔改版的文件（自定义新增）
diff -rq matsuri-singbox official-singbox | grep -i 'only in' | grep matsuri-singbox

# 4) 找出被修改的文件（对官方的 patch）
diff -rq matsuri-singbox official-singbox | grep -i 'differ'

# 5) 关键验证：option/ 包是否有任何差异（无输出 = 完全相同）
diff -rq matsuri-singbox/option official-singbox/option

# 6) 查看 NB4A 真实构建脚本与依赖
cat nb4a/libcore/build.sh nb4a/libcore/init.sh
grep -iE 'sing-box|sagernet/sing |replace' nb4a/libcore/go.mod
```

## 附录 C：参考链接

- MatsuriDayo/sing-box（1.12.x = 1.12.19-neko-1）: https://github.com/MatsuriDayo/sing-box
- MatsuriDayo/NekoBoxForAndroid（v1.4.2）: https://github.com/MatsuriDayo/NekoBoxForAndroid
- SagerNet/sing-box（官方）: https://github.com/SagerNet/sing-box
- 官方 Changelog: https://sing-box.sagernet.org/changelog/
- 官方 Deprecated 字段说明: https://sing-box.sagernet.org/deprecated/
