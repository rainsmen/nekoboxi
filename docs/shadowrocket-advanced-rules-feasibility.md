# ThBox 使用 Shadowrocket 高级规则 · 可行性评估

> **评估日期**：2026-08-03
> **评估对象**：本仓库 `rainsmen/nekoboxi`（ThBox），分支 `upgrade/singbox-1.13.14-naive-plugin`（sing-box 1.13.14-neko）
> **规则来源**：[`Johnshall/Shadowrocket-ADBlock-Rules-Forever`](https://github.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever) `release` 分支（2026-08-02 构建产物，实测下载分析）
> **性质**：**仅评估，未修改任何代码**。文中所有 JSON 片段是给出的可执行方案样例，不是已落地的实现。
> **方法**：阅读本仓库 `ConfigBuilder.kt` / `SingBoxOptionsUtil.kt` / `RuleEntity.kt` / `AssetsActivity.kt` 与 `sing-box/option/`、`sing-box/route/rule/` 源码，确认能力边界；实测下载 5 个 Shadowrocket 规则文件并用脚本做全量语法统计与格式转换验证。

---

## 0. TL;DR

**能用，而且绝大部分能无损用上——但不是"导入 .conf 文件"这种用法，而是"把 .conf 转成 sing-box rule-set 再引用"。**

三句话结论：

1. **规则内容层面几乎 100% 可用**：实测 `sr_adb.conf` 共 61 819 条规则行，其中 61 818 条（99.998%）可以逐条无损映射成 sing-box 的 `domain_suffix` / `domain_keyword` / `ip_cidr`，唯一需要特殊处理的是 1 条 `FINAL`（对应 `route.final`）。
2. **不能用的是"非分流"能力**：`[MITM]`、`[URL Rewrite]`、`SCRIPT`、`REJECT-IMG` / `REJECT-DICT` / `REJECT-200` 这类需要 HTTPS 中间人解密和伪造 HTTP 响应的功能，sing-box 内核**根本没有**这套机制，任何改造都做不出来，只能舍弃或降级为普通 reject。
3. **今天不改一行代码就能落地**：ThBox 的「设置 → 全局自定义配置」会用 `mergeJSON` 合并进最终 sing-box 配置，并且支持 `rules+` / `rule_set+` 这种**追加语义**。把转换好的规则集挂上去即可（详见方案 A）。改代码只是为了把这个过程产品化（方案 B / C）。

一句话建议：**先按方案 A 验证效果（0.5 人日），确认收益后再投入方案 B（2–4 人日）把它变成正式功能。方案 C 不建议做。**

---

## 1. 现状：ThBox 规则系统的能力边界

### 1.1 UI 层能配的字段（`app/src/main/res/xml/route_preferences.xml`）

| UI 字段 | 数据库列（`RuleEntity`） | 生成的 sing-box 字段 |
|---|---|---|
| name | `name` | —（仅展示） |
| 自定义配置 | `config` | 整条规则的 JSON 覆盖（`_hack_custom_config`） |
| apps | `packages` | `package_name` |
| domain | `domains` | `domain` / `domain_suffix` / `domain_keyword` / `domain_regex` / `rule_set` |
| dst ip | `ip` | `ip_cidr` / `ip_is_private` / `rule_set` |
| dst port / src port | `port` / `sourcePort` | `port` / `port_range` / `source_port*` |
| src ip | `source` | `source_ip_cidr` |
| network / protocol | `network` / `protocol` | `network` / `protocol` |
| outbound | `outbound` | `proxy` / `bypass` / `block`(→`action: reject`) / 指定节点 |

### 1.2 domain 字段的前缀语法（`SingBoxOptionsUtil.kt:97-146`）

```
geosite:xxx   → rule_set（本地 geosite.db 查询）
full:xxx      → domain
domain:xxx    → domain_suffix
regexp:xxx    → domain_regex
keyword:xxx   → domain_keyword
（无前缀）     → domain_suffix
```

### 1.3 关键限制（这就是"过于简单"的具体成因）

| # | 限制 | 代码位置 | 影响 |
|---|---|---|---|
| L1 | **规则集只能是本地 geosite/geoip** —— `generateRuleSet()` 只会产出 `type: "local"` + `path: "geosite:xxx"`，没有任何路径能生成 `type: "remote"` 的 rule-set | `SingBoxOptionsUtil.kt:73-95` | 无法通过 UI 引用外部 `.srs` / `.json` 规则集，只能吃 `geosite.db` 里已有的分类 |
| L2 | **资产导入只认 `.db`** —— `if (!fileName.endsWith(".db")) { alert(route_not_asset) }` | `AssetsActivity.kt:105` | `.srs` 文件无法通过 UI 导入；只能用文件管理器手动丢进 `Android/data/io.github.rainsmen.thbox/files/` |
| L3 | **规则内容靠手输** —— 域名列表是一个 `EditTextPreference` 多行文本 | `route_preferences.xml` | 6 万条广告域名这种量级完全不可能手动维护 |
| L4 | **正常运行时没有 `cache_file`** —— 只有 `forTest` 和开启 Clash API 时才生成 `experimental` | `ConfigBuilder.kt:164-178` | remote rule-set 无法持久化缓存，**每次启动都要重新下载**（见 §5 风险 R2） |
| L5 | 没有"规则订阅"概念 —— 只有节点订阅（`GroupUpdater`），没有规则订阅的定时更新 | `group/` | 规则更新只能靠手动 |

### 1.4 但是：已经存在两个"逃生舱"

这是本次评估最重要的发现，**它让方案 A 成为可能**：

**逃生舱 1 —— 全局自定义配置**（`ConfigBuilder.kt:805`）

```kotlin
if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
```

入口在「设置 → 全局自定义配置」（`global_preferences.xml:94`），内容会被 `Util.mergeJSON()` 合并进最终配置。

**逃生舱 2 —— 合并语义支持数组追加**（`Util.kt:129-153`）

```kotlin
if (k.startsWith("+"))      // "+rules" → 前置（prepend）
else if (k.endsWith("+"))   // "rules+" → 追加（append）
else                        // "rules"  → 整体覆盖（危险）
```

而且 `mergeMap` 是**递归**的，所以 `{"route": {"rules+": [...]}}` 能正确地把规则追加到 ThBox 自己生成的 `route.rules` 末尾，而不是把它整个冲掉。

**逃生舱 3 —— 单条规则的自定义 JSON**（`ConfigBuilder.kt:633`）：`RuleEntity.config` 会 merge 到该条规则对象上，可以给单条规则塞 `rule_set`、`invert`、`action` 等 UI 没暴露的字段。

### 1.5 内核侧（sing-box 1.13.14）的能力是充足的

实测 `sing-box/option/rule_set.go`：

- `type`: `inline` / `local` / `remote` **三种都支持**
- `remote` 支持 `url` + `download_detour` + `update_interval`（默认 24h）
- `format`: `source`（JSON）/ `binary`（.srs），按扩展名自动推断
- 规则支持 `type: "logical"` + `mode: and/or` + `invert`（对应 Shadowrocket 的 AND / OR / NOT）
- `action`: `route` / `reject`（`method: default|drop|reply`）/ `hijack-dns` / `sniff` / `resolve`
- DNS 规则同样支持 `rule_set`（`option/rule_dns.go:109`）

**结论：瓶颈 100% 在 App 侧（L1–L5），不在内核。**

---

## 2. Shadowrocket 规则实测剖析

### 2.1 仓库文件清单（实测 2026-08-02 构建产物）

| 文件 | 体积 | 用途 |
|---|---|---|
| `sr_adb.conf` | 2.35 MiB | 黑名单（GFWList）+ 去广告 |
| `sr_ad_only.conf` | 2.32 MiB | 仅去广告 |
| `sr_top500_whitelist.conf` | 30 KiB | 白名单（默认代理） |
| `sr_top500_whitelist_ad.conf` | 2.35 MiB | 白名单 + 去广告 |
| `sr_cnip.conf` | 16 KiB | 国内外划分 |
| `sr_backcn.conf` | 1.3 KiB | 回国规则 |
| `lazy_group.conf` | 27 KiB | 懒人配置（含策略组 + 34 条外部 RULE-SET） |
| 其余 `sr_*_ad.conf` | ~2.4 MiB | 上述各版本 + 广告库 |

### 2.2 规则类型分布（脚本全量统计，非抽样）

| 文件 | DOMAIN-SUFFIX | IP-CIDR | DOMAIN-KEYWORD | GEOIP | RULE-SET | FINAL |
|---|---|---|---|---|---|---|
| `sr_adb.conf` | 61 663 | 152 | 3 | 0 | 0 | 1 |
| `sr_ad_only.conf` | 60 969 | 128 | 0 | 0 | 0 | 0 |
| `sr_top500_whitelist.conf` | 751 | 24 | 2 | 1 | 1 | 1 |
| `sr_cnip.conf` | 371 | 17 | 2 | 1 | 0 | 1 |
| `lazy_group.conf` | 3 | 0 | 0 | 1 | 34 | 1 |

**这套规则实际只用到 6 种规则类型**，比 Shadowrocket 支持的全集（见 §3.1）窄得多。所谓"高级"，体现在**数据规模和更新频率**（每日 8:00 自动构建，源自 GFWList + Greatfire + EasyList + EasyList China + Peter Lowe + 乘风规则），而不是规则语法的复杂度。

### 2.3 段落结构

| 段落 | `sr_adb.conf` | `lazy_group.conf` | sing-box 可映射性 |
|---|---|---|---|
| `[General]` | 5 行 | 14 行 | 部分（见 §3.3） |
| `[Rule]` | 61 819 行 | 39 行 | **几乎全部可映射** |
| `[Proxy Group]` | — | 23 行 | 部分（需手工建 selector/urltest） |
| `[Host]` | — | 3 行 | 可映射（DNS predefined） |
| `[URL Rewrite]` | 1 行 | 2 行 | **不可映射** |
| `[MITM]` | 1 行 | 1 行 | **不可映射** |

---

## 3. Shadowrocket → sing-box 1.13 语义映射矩阵

### 3.1 规则类型

| Shadowrocket | sing-box 等价 | 结论 | 备注 |
|---|---|---|---|
| `DOMAIN` | `domain` | ✅ 无损 | |
| `DOMAIN-SUFFIX` | `domain_suffix` | ✅ 无损 | 语义一致（后缀匹配） |
| `DOMAIN-KEYWORD` | `domain_keyword` | ✅ 无损 | |
| `DOMAIN-WILDCARD` | `domain_regex` | ⚠️ 需转义 | `*`→`.*`、`?`→`.`，本规则集未使用 |
| `IP-CIDR` / `IP-CIDR6` | `ip_cidr` | ✅ 无损 | v4/v6 共用一个字段 |
| `IP-CIDR,...,no-resolve` | `ip_cidr`（不加 `resolve` action） | ✅ 语义近似 | sing-box 用独立的 `action: resolve` 规则控制是否解析，粒度不同但可等效 |
| `GEOIP,CN` | `rule_set: ["geoip:cn"]` | ✅ 无损 | ThBox 已内置 `geoip.db` |
| `IP-ASN` | — | ❌ 无原生支持 | 只能预先展开成 `ip_cidr` 列表；本规则集未使用 |
| `RULE-SET,<url>` | `rule_set` + `type: remote` | ✅ 结构等价 | 但**引用的 `.list` 文件本身也要转换**（递归） |
| `DOMAIN-SET,<url>` | `rule_set`（纯域名列表需转 JSON） | ⚠️ 需转换 | |
| `DST-PORT` | `port` / `port_range` | ✅ 无损 | |
| `PROTOCOL,UDP` | `network: ["udp"]` | ✅ 无损 | |
| `AND` / `OR` / `NOT` | `type: "logical"` + `mode` + `invert` | ✅ 结构等价 | |
| `USER-AGENT` | — | ❌ 不可能 | 需解 HTTP 头，sing-box 不做 |
| `URL-REGEX` | — | ❌ 不可能 | 需解 HTTPS 明文 URL，需 MITM |
| `SCRIPT` | — | ❌ 不可能 | 无 JS 运行时 |
| `FINAL` | `route.final` | ✅ 但需特殊处理 | 不是规则，是全局兜底；ThBox 已固定为 `proxy`（`ConfigBuilder.kt:249`） |

### 3.2 策略（Policy）

| Shadowrocket | ThBox / sing-box | 结论 |
|---|---|---|
| `DIRECT` | `outbound: "bypass"`（UI 的"绕过"，tag 见 `ConfigBuilder.kt:51`） | ✅ |
| `PROXY` | `outbound: "proxy"` | ✅ |
| `REJECT` | `action: "reject"` | ✅ |
| `REJECT-DROP` | `action: "reject", method: "drop"` | ✅ |
| `REJECT-NO-DROP` | `action: "reject", no_drop: true` | ✅ |
| `REJECT-IMG` / `-DICT` / `-ARRAY` / `-200` / `-TINYGIF` / `-VIDEO` | — | ❌ **需要伪造 HTTP 响应体，做不到**。可降级为普通 `reject`，或在 DNS 层用 `action: "predefined"` 返回 `0.0.0.0` 近似 |
| `TAILSCALE` | Tailscale endpoint（ThBox 已支持） | ⚠️ 需手工指到具体 endpoint tag |
| 策略组名（如 `AI`、`YOUTUBE`） | `selector` / `urltest` outbound | ⚠️ 需在 ThBox 侧手工建组，无法从 conf 自动生成节点 |

### 3.3 `[General]` 段

| Shadowrocket 键 | sing-box 对应 | 结论 |
|---|---|---|
| `dns-server` | `dns.servers`（DoH/DoQ/DoT 均支持） | ✅ ThBox 设置里已有 |
| `skip-proxy` / `bypass-tun` | `route.rules` + `ip_is_private` / TUN `route_exclude_address` | ✅ 语义近似 |
| `ipv6` / `prefer-ipv6` | `domain_strategy` | ✅ ThBox 设置里已有 |
| `hijack-dns` | `action: "hijack-dns"` | ✅ ThBox 已默认生成（`ConfigBuilder.kt:738-745`） |
| `block-quic` | 一条 `network:udp` + `port:443` + reject 规则 | ✅ ThBox 默认规则里已有（`ProfileManager.kt:196`） |
| `private-ip-answer` / `dns-direct-fallback-proxy` | — | ❌ Shadowrocket 特有的启发式行为，无等价物 |
| `always-real-ip` / `icmp-auto-reply` | — | ❌ |

### 3.4 其他段落

| 段落 | 结论 |
|---|---|
| `[Host]`（静态 DNS 映射） | ⚠️ 可用 sing-box 1.13 的 DNS `action: "predefined"` 实现，需逐条转换 |
| `[Proxy]`（节点） | ⚠️ 与本议题无关；ThBox 有自己的订阅体系 |
| `[Proxy Group]`（`url-test` + `policy-regex-filter`） | ⚠️ sing-box 有 `urltest`，但**没有按节点名正则筛选**的功能，需要在 App 侧实现分组逻辑 |
| `[URL Rewrite]` | ❌ 需 MITM |
| `[MITM]` | ❌ sing-box 无 HTTPS 解密能力，架构上不具备 |

### 3.5 映射结论量化

对 `sr_adb.conf`（最大最完整的一个）实测：

```
总规则行:        61 819
可无损逐条映射:  61 818  (99.998%)
需特殊处理:      1 (FINAL → route.final)
不可映射:        0
段外功能损失:    [URL Rewrite] 1 行 + [MITM] 1 行
```

**即：这套规则集的"分流与去广告"能力可以完整迁移到 ThBox；丢掉的只有 1 条 Google AMP 跳转重写和 MITM 声明。**

---

## 4. 三条实施路线

### 方案 A：零改码，用全局自定义配置引用外部规则集 ⭐ 推荐先做

**思路**：conf → sing-box rule-set（在 PC / GitHub Actions 上转换并托管）→ ThBox 全局自定义配置里用 `rule_set+` / `rules+` 挂上去。

**样例配置**（粘贴进「设置 → 全局自定义配置」）：

```json
{
  "experimental": {
    "cache_file": {
      "enabled": true,
      "path": "cache.db",
      "store_rdrc": true
    }
  },
  "route": {
    "rule_set+": [
      {
        "type": "remote",
        "tag": "sr-reject",
        "format": "binary",
        "url": "https://raw.githubusercontent.com/<你的仓库>/main/srs/sr_ad_only.srs",
        "download_detour": "direct",
        "update_interval": "24h"
      },
      {
        "type": "remote",
        "tag": "sr-direct",
        "format": "binary",
        "url": "https://raw.githubusercontent.com/<你的仓库>/main/srs/sr_direct.srs",
        "download_detour": "direct",
        "update_interval": "24h"
      },
      {
        "type": "remote",
        "tag": "sr-proxy",
        "format": "binary",
        "url": "https://raw.githubusercontent.com/<你的仓库>/main/srs/sr_proxy.srs",
        "download_detour": "direct",
        "update_interval": "24h"
      }
    ],
    "rules+": [
      { "rule_set": ["sr-reject"], "action": "reject" },
      { "rule_set": ["sr-direct"], "outbound": "bypass" },
      { "rule_set": ["sr-proxy"],  "outbound": "proxy" }
    ]
  },
  "dns": {
    "rules+": [
      { "rule_set": ["sr-direct"], "server": "dns-direct" }
    ]
  }
}
```

**必须注意的四点**：

1. **一定用 `rules+`（追加）而不是 `rules`（覆盖）或 `+rules`（前置）**。ThBox 生成的 `route.rules[0]` 是 `action: "sniff"`，`+rules` 会把自定义规则插到嗅探之前，**导致域名类规则整体失效**；`rules` 则会把内置的 hijack-dns / 组播拦截全部冲掉。
2. **`experimental.cache_file` 必须自己补上**（L4）。不补的话每次启动都要同步下载规则集，见风险 R2。
3. **`download_detour` 建议设 `direct`**：设成走代理会形成"要连代理必须先下规则、下规则又要先连代理"的启动死锁；但设 `direct` 意味着规则集 URL 必须在墙内可直连（自建 CDN / jsDelivr 镜像 / gitee）。
4. **DNS 分流不会自动跟随**。ThBox 的 UI 规则会同时生成 route 规则和 DNS 规则，而自定义配置注入的只是 route 规则，直连域名仍会走 `dns-remote` 拿到境外 CDN 地址——所以上面的样例补了 `dns.rules+`。**若开启了 FakeDNS，追加的 DNS 规则会被排在 fakeip 规则之后而基本不生效**（`ConfigBuilder.kt:769-780`），此时只能改用方案 B。

**转换工具**：sing-box 自带的 `sing-box rule-set convert` **只支持 adguard 源类型**（实测 `cmd/sing-box/cmd_rule_set_convert.go:36`），不支持 Surge/Shadowrocket 语法。所以需要一个约 100 行的 Python/Go 脚本按策略拆桶生成 JSON，再用 `sing-box rule-set compile` 编成 `.srs`。解析时必须处理的细节（实测踩到）：

- 行尾注释：`DOMAIN-SUFFIX,x.com,PROXY # GOOGLE AMP ISSUE#237` —— 不切掉会得到一个叫 `PROXY # GOOGLE AMP ISSUE#237` 的假策略
- 参数后缀：`,no-resolve`
- 策略名**大小写混用**：实测 `sr_adb.conf` 里同时存在 `Direct`(101) 和 `DIRECT`(7)、`Reject`(61 097)、`Proxy`(613)
- `RULE-SET` 引用的外部 `.list` 需要递归拉取转换（`lazy_group.conf` 有 34 条）

**成本**：转换脚本 + 托管（GitHub Actions 每日同步）约 0.5 人日；App 侧 0 改动。

---

### 方案 B：小改 App，把规则集变成一等公民 ⭐ 推荐正式做

在方案 A 验证有效后，把手工步骤产品化。四个改动点，互相独立：

| 改动 | 位置 | 说明 | 估工 |
|---|---|---|---|
| B1 | `ConfigBuilder.kt:164` | 正常运行时也生成 `experimental.cache_file`（`enabled: true`），让 remote rule-set 能持久化缓存 | 0.2 人日 |
| B2 | `SingBoxOptionsUtil.kt:73-95` + `RuleEntity` | domain/ip 字段新增前缀：`ruleset-url:https://...` → 生成 `type: remote`；`ruleset:filename.srs` → 生成 `type: local`。**改动面极小**，因为 `generateRuleSet()` 已经是唯一出口 | 0.5 人日 |
| B3 | `AssetsActivity.kt:105` | 放开导入白名单，允许 `.srs` / `.json`，并在列表中展示 | 0.5 人日 |
| B4 | `ConfigBuilder.kt:596-624` | 让引用 rule-set 的规则同样生成对应 DNS 规则（复用现有 `makeDnsRuleObj()` 路径），解决方案 A 的第 4 点 | 0.5 人日 |

改完之后用户体验是：**在规则页新建一条规则 → domain 填 `ruleset-url:https://.../sr_ad_only.srs` → outbound 选"阻止" → 完事**，且 DNS 分流自动跟随、规则集自动按 `update_interval` 更新、重启不用重下。

**成本**：2–4 人日（含测试）。**风险低**，都是在既有代码路径上加分支，不动核心流程。

---

### 方案 C：内置 Shadowrocket conf 解析器 + 规则订阅 ❌ 不建议

即在 App 内直接吃 `.conf`：新增 `RuleSubscription` 实体 + conf 解析器 + 定时更新 + 策略组映射 UI。

**不建议的理由**：

1. **收益重合**：方案 B 已经解决 95% 的实际需求，C 只是把"在 PC 上转一次"挪到手机上。
2. **成本高**：解析器 + 数据库迁移 + 新 UI + 订阅更新调度，8–15 人日。
3. **持续维护负担**：Shadowrocket 语法由闭源客户端定义，随版本增删（`policy-regex-filter`、`REJECT-NO-DROP` 都是近年新增的），跟进成本无上限。
4. **性能不划算**：6 万条规则在手机上解析成 JSON 再交给 gson，实测 source JSON 就有 1.21 MiB，每次启动都要走一遍；而 `.srs` 是预编译二进制，内核直接 mmap 式读取。**转换本该在构建期做，不该在启动期做。**

如果一定要做，正确的落点是 **libcore 里加一个 Go 侧转换函数**（复用 `common/srs` 的 `srs.Write`），把 conf 转成 `.srs` 落盘一次，而不是每次启动都解析。

---

### 方案对比

| | A（零改码） | B（小改） | C（内置解析） |
|---|---|---|---|
| App 改动 | 无 | ~4 处 | 大量 + DB 迁移 |
| 用户操作成本 | 高（要自己转换托管） | 低 | 最低 |
| 估工 | 0.5 人日 | 2–4 人日 | 8–15 人日 |
| 维护风险 | 低 | 低 | 高（追随闭源语法） |
| DNS 分流联动 | 需手工 | 自动 | 自动 |
| 建议 | **先做，验证收益** | **再做，正式落地** | 不做 |

---

## 5. 风险清单

| ID | 风险 | 触发条件 | 缓解 |
|---|---|---|---|
| R1 | **启动死锁**：remote rule-set 走代理下载，代理又要等规则集加载完才起来 | `download_detour` 指向 `proxy` | 固定用 `direct`，并保证 URL 墙内可达（gitee / jsDelivr / 自建） |
| R2 | **每次启动阻塞下载**：无 `cache_file` 时 `lastUpdated` 恒为零值，`StartContext` 会同步 `fetch()`，**失败直接返回 error 导致整个服务起不来**（`route/rule/rule_set_remote.go:106-111`） | L4 未修复 | 方案 A 手工补 `cache_file`；方案 B1 固化 |
| R3 | **`+rules` 破坏 sniff/DNS 顺序** | 用了前置合并 | 只用 `rules+`；`dns.rules` 的 `[0]` 是防环规则（`outbound: any → dns-direct`），前置会造成 DNS 循环 |
| R4 | **FakeDNS 抢先匹配**，追加的 DNS 规则不生效 | 开启 FakeDNS + 方案 A | 关 FakeDNS，或走方案 B4 |
| R5 | **规则冲突/顺序**：ThBox UI 规则永远排在自定义追加规则之前 | 两套规则并存 | 明确"UI 规则优先"，或把默认的 `geosite:cn` 绕过规则关掉，统一由 Shadowrocket 规则接管 |
| R6 | **`.srs` 无法通过 UI 导入** | L2 | 方案 A 用 remote；方案 B3 放开 |
| R7 | **广告 reject 效果差异**：sing-box 只能 RST/drop，无法返回空图/空 JSON | 固有差异 | 部分 App 会重试；可在 DNS 层用 `action: predefined` 返回 `0.0.0.0` 缓解 |
| R8 | **规则集体积影响内存**：6.1 万条 domain_suffix 会在内核里建 domain trie | 全量导入 | 见 §6，实测量级可接受 |

---

## 6. 体积与性能实测

| 项目 | 实测值 |
|---|---|
| `sr_ad_only.conf` 原始 | 2.32 MiB / 61 097 条 |
| → 转 sing-box source JSON | **1.21 MiB** |
| → gzip 后 | 512 KiB |
| → 预估 `.srs` 二进制 | **约 300–450 KiB**（参照 MetaCubeX `geosite/cn.srs` = 438 KiB，量级相当）※ 本机无 Go 工具链，未能实测编译 |
| `sr_top500_whitelist.conf` → JSON | DIRECT 385 条 / 6.2 KiB + PROXY 389 条 / 7.6 KiB |

**性能判断**：sing-box 的域名匹配是 trie/succinct 结构（`sing/common/domain.Matcher`），6 万条后缀属于常规量级——对比 ThBox 已经在用的 `geosite.db` 本身就是几十万条规模。**规则条数不构成性能问题**，内存增量预计在 10 MiB 以内。

---

## 7. 一个必须先回答的问题：值不值得用？

"能不能用"的答案是能。但在动手前建议先看清楚**增量价值在哪**：

| 维度 | Shadowrocket 规则集 | ThBox 现状 | 增量 |
|---|---|---|---|
| **广告拦截** | 61 097 条（EasyList + EasyList China + Peter Lowe + 乘风 + 自定义） | `geosite:category-ads-all`（默认规则已启用）；MetaCubeX sing 分支同名规则集实测仅 **907 条**（SagerNet 官方 geosite.db 版本未实测，可能更大） | **显著**，这是最大卖点 |
| **GFW 黑名单** | GFWList + Greatfire 检测结果 | `geosite:geolocation-!cn` 等 | 有限，sing-box 生态覆盖已很好 |
| **国内直连** | top500 白名单 + apple CDN 优化 | `geosite:cn` + `geoip:cn`（默认已启用） | 有限 |
| **分应用/游戏平台分流** | `lazy_group.conf` 34 个 RULE-SET | 需手工建 | 中等，但同样能用 sing-box 生态的等价规则集 |
| **MITM / URL 重写 / 脚本** | 有 | 无，且**架构上不可能有** | 0 |

**更省事的替代路线**（如果目标只是"规则更强"而非"必须是 Shadowrocket 那一份"）：

1. **去广告**：`privacy-protection-tools/anti-AD` 提供 `anti-ad-adguard.txt`，而 sing-box **自带** adguard 转换器（`sing-box rule-set convert -t adguard`）——不用写任何解析代码，一条命令出 `.srs`。
2. **分流**：`MetaCubeX/meta-rules-dat` 的 `sing` 分支已经提供**全套预编译 `.srs`**（实测 `https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/*.srs` 可直接下载），涵盖 geosite / geoip / 各大服务分类，配合方案 A/B 直接 `type: remote` 引用即可，零转换成本。

**推荐组合**：方案 B 打底（让 App 能引用任意 rule-set）+ 广告用 anti-AD/Shadowrocket 广告库转 `.srs` + 分流用 meta-rules-dat 现成 `.srs`。这样既拿到了 Shadowrocket 规则最有价值的部分（超大广告库），又避免了跟随一套闭源客户端的私有语法。

---

## 8. 建议的推进顺序

1. **第 1 步（0.5 人日）**：写转换脚本，把 `sr_ad_only.conf` 转成 `.srs`，托管到自己的仓库（GitHub Actions 每日同步上游 8:00 构建产物）。按方案 A 的样例配置贴进全局自定义配置，实机验证：启动耗时、广告拦截效果、分流正确性、内存占用。
2. **第 2 步（判断）**：若第 1 步收益明显（尤其是广告拦截），进入方案 B；若收益一般，只保留方案 A 作为文档说明即可。
3. **第 3 步（2–4 人日）**：实施 B1–B4，把 rule-set 变成 UI 一等公民。B1（cache_file）无论如何都该做——它对现有功能也是纯收益。
4. **第 4 步（可选）**：在 README 或应用内文档里给出推荐规则集清单（meta-rules-dat / anti-AD / 自建 Shadowrocket 转换产物）。

---

## 附录 A：验证过的关键代码位置

| 结论 | 位置 |
|---|---|
| 规则实体字段 | `app/src/main/java/io/nekohasekai/sagernet/database/RuleEntity.kt:12-28` |
| domain 前缀语法 | `app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt:97-146` |
| rule_set 只生成 local | `app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt:73-95` |
| 全局自定义配置合并 | `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt:805-808` |
| `key+` / `+key` 合并语义 | `app/src/main/java/moe/matsuri/nb4a/utils/Util.kt:129-153` |
| 缺 cache_file | `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt:164-178` |
| 资产导入只认 `.db` | `app/src/main/java/io/nekohasekai/sagernet/ui/AssetsActivity.kt:105` |
| outbound tag 常量 | `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt:47-52` |
| sniff/resolve 规则位于 rules[0] | `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt:256-267` |
| 默认规则（QUIC/广告/CN） | `app/src/main/java/io/nekohasekai/sagernet/database/ProfileManager.kt:190-232` |
| remote rule-set 支持 | `sing-box/option/rule_set.go:122-127` |
| 无缓存则同步下载且失败即报错 | `sing-box/route/rule/rule_set_remote.go:97-111` |
| convert 只支持 adguard | `sing-box/cmd/sing-box/cmd_rule_set_convert.go:36` |
| geosite: 本地查询 hook | `libcore/geosite.go:46-53`、`sing-box/route/rule/rule_set_local.go:59-78` |

## 附录 B：转换脚本要点

```
按 [段落] 切分 → 只取 [Rule] 段
逐行:
  跳过空行与 # 开头
  切掉行尾 " #..." 注释
  按 , 切分 → [类型, 值, 策略, 可选参数...]
  策略 upper() 归一（Direct/DIRECT → DIRECT）
  丢弃 no-resolve 等参数（或据此决定是否生成 resolve 规则）
按策略分桶（DIRECT / PROXY / REJECT / 自定义组名）
每桶输出一个 {"version":3,"rules":[{"domain_suffix":[...],"domain_keyword":[...],"ip_cidr":[...]}]}
  → sing-box rule-set compile x.json -o x.srs
单独处理: FINAL（→ route.final）、GEOIP（→ geoip: rule_set）、RULE-SET（递归拉取外部 .list）
```

（本次评估使用的一次性验证脚本位于 `/tmp/sr2srs.py`，未纳入仓库。）
