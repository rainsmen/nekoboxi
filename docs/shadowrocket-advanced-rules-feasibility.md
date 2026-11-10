# Shadowrocket 规则在 ThBox 中的使用边界

更新时间：2026-11-10。基线：本分支固定的 sing-box 1.14.4 核心。本文是转换方案与验收要求，仓库尚未实现通用 Shadowrocket `.conf` 转换器或规则订阅 UI。

早期评估中“按策略把全文件分成 DIRECT/PROXY/REJECT 三桶”的方案已废弃：它会改变首次匹配顺序。旧样本的规则数量、性能估计和工期不作为当前支持或验收证据。

## 当前可以做什么

资源页支持导入并显示 `.srs`、规则集 `.json` 和 GeoIP/GeoSite `.db`。导入完成不等于规则已启用；`.srs/.json` 需要在全局自定义配置中定义 `route.rule_set`，然后由规则引用 tag。

以下例子引用已经转换为 sing-box 格式的本地规则集。将 `path` 换成资源详情显示的当前应用绝对路径；Preview 与正式版的私有目录不同。

```json
{
  "route": {
    "rule_set+": [
      {
        "type": "local",
        "tag": "custom-adblock",
        "format": "binary",
        "path": "/replace/with/the/path/shown/in/assets/adblock.srs"
      }
    ],
    "rules+": [
      { "rule_set": ["custom-adblock"], "action": "reject" }
    ]
  }
}
```

JSON 源规则集使用 `format: source`。该例子只增加流量拒绝规则，不宣称实现与 Shadowrocket 完全相同的 DNS 或 HTTP 拦截行为。

`rules+` 追加到应用生成规则之后，先前已命中的规则仍然优先；`+rules` 则放在嗅探和 DNS 劫持之前，不能无条件使用。直接使用 `rules` 会替换整个数组。合并入口为 `Util.mergeJSON`，规则顺序必须结合最终导出配置检查。

## 转换支持边界

| 输入 | 可采用的目标表达 | 必须验证的条件 |
|---|---|---|
| DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD | domain / domain_suffix / domain_keyword | 规范化与原始优先级 |
| IP-CIDR / IP-CIDR6 | ip_cidr | IPv4/IPv6、重叠网段与解析时机 |
| GEOIP | 本地 GeoIP 规则集 | 分类存在、数据版本与原客户端差异 |
| DST-PORT、UDP/TCP 条件 | port、network | 区分传输网络与应用层 protocol |
| AND / OR / NOT | logical、mode、invert | 保留嵌套结构和组合语义 |
| RULE-SET / DOMAIN-SET | 本地或远程规则集 | 引用内容也需转换，并保留引用插入的位置 |
| DIRECT / PROXY / REJECT | bypass / proxy / reject | 策略组名需明确映射到存在的出站 |
| FINAL | 最终策略或等价终止规则 | 原文件出现 FINAL 后的规则不能继续参与匹配 |
| no-resolve | 保持原始解析时机的条件与动作 | 不能仅删除该参数；不能保证等价时应拒绝无损转换 |
| URL Rewrite / MITM / SCRIPT / 伪造图片或 HTTP 响应 | 无直接映射 | 明确报告不支持；不得静默降级成同等能力 |

支持字段并不能证明整份配置可以无损转换。目标核心的规则集格式也不包含所有客户端策略和 DNS 行为。

## 保持规则顺序

原规则：

```text
DOMAIN,a.example.com,DIRECT
DOMAIN-SUFFIX,example.com,PROXY
DOMAIN,b.example.com,DIRECT
```

应当得到：a 直连，b 代理。将两个 DIRECT 规则合并并放到前面会让 b 错误直连；把 PROXY 桶放到前面又会让 a 错误代理。

转换器应保留原始顺序，仅合并连续且可证明等价的规则片段。每个片段使用独立 tag，引用顺序保持不变。进一步合并需要证明规则互不重叠，不能仅因为策略相同就合并。

建议流程：

1. 解析段落，仅将明确支持的 `[Rule]` 内容送入转换。
2. 正确处理行尾注释、策略名大小写、额外参数和空白。
3. 逐行保留文件名、行号、策略、条件和 `no-resolve` 等语义。
4. 递归转换外部引用，检测引用环、下载失败和不支持格式。
5. 生成有序片段及策略映射，输出所有无法等价转换的条目。
6. 使用当前固定核心编译 `.srs`，通过差异用例后再导入。

不支持的条目应导致转换失败，或者由用户明确确认跳过；结果不能继续标记为“无损”。

## DNS 与下载

流量规则和 DNS 规则必须分开验证。应用只为可等价表达的 UI 条件派生 DNS 规则；自定义 `route.rules+` 不会自动生成对应 DNS 行为。向 DNS 数组末尾追加规则也可能被前面的 FakeDNS 规则覆盖。

远程规则集需要检查 URL 可达性、下载所用出站、启动依赖、缓存与更新失败行为。不要把“总能直连下载”或“所有代理下载都会死锁”作为前提；应使用实际配置验证。当前普通运行已启用 cache_file，不应沿用早期文档中“只有测速时才启用缓存”的判断。

## 方案与验收

可以先人工转换小规模规则集，通过资源页与自定义配置验证。将规则集做成可选择、可订阅和可更新的 UI，是后续独立功能；当前文件列表与使用提示不等于该功能已经完成。

转换验收至少包含：域名例外、重叠 CIDR、跨段相同策略、FINAL 终止、外部引用插入位置、引用环、no-resolve 的实际解析次数、未知策略、FakeDNS 开/关，以及存在应用内规则时的最终优先级。

性能必须使用当前转换产物测量启动耗时、内存和规则命中；历史规则行数或二进制体积不能替代这些测量。工期应在语义及验收范围确定后估算。
