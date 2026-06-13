# Naive Outbound API 参考

本文档提供 Naive outbound 实现的完整 API 参考，包括所有数据类、函数和配置选项。

---

## 数据类

### NaiveBean

**包名**: `io.nekohasekai.sagernet.fmt.naive`

**用途**: 用户配置的 Naive 节点数据模型，用于 UI 层和持久化存储。

#### 字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `serverAddress` | String | `""` | 服务器地址（域名或 IP） |
| `serverPort` | Int | `443` | 服务器端口号 |
| `username` | String | `""` | 认证用户名 |
| `password` | String | `""` | 认证密码 |
| `proto` | String | `"https"` | 协议类型：`"https"` 或 `"quic"` |
| `sni` | String | `""` | TLS SNI（Server Name Indication），可选 |
| `certificates` | String | `""` | 自定义 TLS 证书内容（PEM 格式），可选 |
| `extraHeaders` | String | `""` | 额外的 HTTP 请求头（多行文本格式），可选 |
| `insecureConcurrency` | Int | `0` | 允许的并发连接数，0 表示不限制 |

#### 示例

```kotlin
val bean = NaiveBean().apply {
    serverAddress = "proxy.example.com"
    serverPort = 443
    username = "user123"
    password = "pass456"
    proto = "https"
    sni = "custom.sni.com"
    extraHeaders = """
        User-Agent: MyApp/1.0
        X-Custom-ID: 12345
    """.trimIndent()
    insecureConcurrency = 5
}
```

#### Extra Headers 格式

**输入格式**（字符串）:
```
Header-Name-1: value1
Header-Name-2: value2
Header-Name-3: value3
```

**规则**:
- 每行一个 header
- 格式：`名称: 值`
- 名称和值会自动 trim 空格
- 空行和格式错误的行会被忽略

---

### Outbound_NaiveOptions

**包名**: `moe.matsuri.nb4a.SingBoxOptions`

**用途**: sing-box naive outbound 配置的强类型表示，用于生成 sing-box 配置 JSON。

#### 字段

##### 基本字段

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `type` | String | ✅ | 固定为 `"naive"` |
| `tag` | String | ✅ | Outbound 标签（由 ConfigBuilder 设置） |

##### 服务器配置 (ServerOptions)

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `server` | String | ✅ | 服务器地址（域名或 IP） |
| `server_port` | Integer | ✅ | 服务器端口号 |

##### 认证配置

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `username` | String | ❌ | 认证用户名 |
| `password` | String | ❌ | 认证密码 |

##### 协议配置

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `network` | String | ❌ | 网络类型：`"tcp"`, `"udp"` 或 `"tcp,udp"` |
| `quic` | Boolean | ❌ | 是否启用 QUIC 协议，默认 `false` |

##### TLS 配置 (OutboundTLSOptionsContainer)

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `tls` | OutboundTLSOptions | ✅ | TLS 配置对象 |

TLS 配置对象字段：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `enabled` | Boolean | ✅ | 是否启用 TLS（naive 必须为 `true`） |
| `server_name` | String | ✅ | TLS SNI，用于证书验证 |
| `certificate` | String | ❌ | 自定义证书内容（PEM 格式） |
| `certificate_path` | String | ❌ | 证书文件路径 |
| `insecure` | Boolean | ❌ | 是否跳过证书验证（不推荐） |
| `alpn` | List<String> | ❌ | 应用层协议协商列表，如 `["h2", "http/1.1"]` |

##### Naive 专用字段

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `insecure_concurrency` | Integer | ❌ | 并发连接数，允许更多并发但降低安全性 |
| `extra_headers` | Map<String, List<String>> | ❌ | 额外的 HTTP 请求头 |

##### Dialer 选项 (DialerOptions)

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `detour` | String | ❌ | 分流到指定 outbound 标签 |
| `bind_interface` | String | ❌ | 绑定到指定网络接口 |
| `inet4_bind_address` | String | ❌ | IPv4 绑定地址 |
| `inet6_bind_address` | String | ❌ | IPv6 绑定地址 |
| `protect_path` | String | ❌ | Android VPN protect socket 路径 |
| `routing_mark` | Integer | ❌ | Linux 路由标记 |
| `reuse_addr` | Boolean | ❌ | 是否复用地址 |
| `connect_timeout` | String | ❌ | 连接超时时间，如 `"30s"` |
| `tcp_fast_open` | Boolean | ❌ | 是否启用 TCP Fast Open |
| `tcp_multi_path` | Boolean | ❌ | 是否启用 TCP Multipath |
| `udp_fragment` | Boolean | ❌ | 是否允许 UDP 分片 |
| `domain_strategy` | String | ❌ | 域名解析策略 |

#### 示例

```java
Outbound_NaiveOptions options = new Outbound_NaiveOptions();
options.type = "naive";
options.server = "proxy.example.com";
options.server_port = 443;
options.username = "user123";
options.password = "pass456";
options.quic = true;

// TLS 配置
options.tls = new OutboundTLSOptions();
options.tls.enabled = true;
options.tls.server_name = "sni.example.com";

// Extra headers
Map<String, List<String>> headers = new HashMap<>();
headers.put("User-Agent", Arrays.asList("MyApp/1.0"));
headers.put("X-Custom-ID", Arrays.asList("12345"));
options.extra_headers = headers;

// 并发配置
options.insecure_concurrency = 5;
```

#### JSON 序列化示例

```json
{
  "type": "naive",
  "server": "proxy.example.com",
  "server_port": 443,
  "username": "user123",
  "password": "pass456",
  "quic": true,
  "tls": {
    "enabled": true,
    "server_name": "sni.example.com"
  },
  "extra_headers": {
    "User-Agent": ["MyApp/1.0"],
    "X-Custom-ID": ["12345"]
  },
  "insecure_concurrency": 5,
  "tag": "proxy"
}
```

---

## 函数

### buildSingBoxOutboundNaiveBean()

**包名**: `io.nekohasekai.sagernet.fmt.naive`

**签名**:
```kotlin
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): Outbound_NaiveOptions
```

**用途**: 将用户配置（NaiveBean）转换为 sing-box 配置（Outbound_NaiveOptions）。

#### 参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `bean` | NaiveBean | 用户配置的 Naive 节点 |

#### 返回值

| 类型 | 说明 |
|------|------|
| Outbound_NaiveOptions | sing-box 配置对象 |

#### 转换规则

1. **基本字段**:
   - `server` ← `serverAddress`
   - `server_port` ← `serverPort`
   - `username` ← `username`（如果非空）
   - `password` ← `password`（如果非空）

2. **QUIC 协议**:
   - 当 `bean.proto == "quic"` 时，设置 `quic = true`

3. **并发连接**:
   - 当 `bean.insecureConcurrency > 0` 时，设置 `insecure_concurrency`

4. **Extra Headers**:
   - 解析 `bean.extraHeaders` 多行文本
   - 转换为 `Map<String, List<String>>` 格式
   - 忽略格式错误的行

5. **TLS 配置**:
   - 总是创建 TLS 配置对象
   - `enabled = true`（naive 必需）
   - `server_name` 使用 `bean.sni`，如果为空则使用 `bean.serverAddress`
   - `certificate` 使用 `bean.certificates`（如果非空）

#### 示例

```kotlin
val bean = NaiveBean().apply {
    serverAddress = "proxy.example.com"
    serverPort = 443
    username = "user"
    password = "pass"
    proto = "https"
}

val options = buildSingBoxOutboundNaiveBean(bean)

// 使用生成的配置
val json = gson.toJson(options)
```

#### 异常

此函数不抛出异常，但会忽略无效的输入：
- 空白的字符串字段不会设置
- 格式错误的 extra headers 行会被跳过
- 无效的并发连接数（≤0）会被忽略

---

### buildDomainResolver()

**包名**: `io.nekohasekai.sagernet.fmt.naive`

**签名**:
```kotlin
fun buildDomainResolver(
    server: String = "dns-direct",
    strategy: String = "",
    forTest: Boolean = false
): Map<String, Any>
```

**用途**: 构造 domain_resolver 配置，用于 outbound 的域名解析。

#### 参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `server` | String | `"dns-direct"` | DNS 服务器标签 |
| `strategy` | String | `""` | 域名解析策略 |
| `forTest` | Boolean | `false` | 是否为测试模式 |

##### DNS 服务器标签

| 标签 | 说明 |
|------|------|
| `"dns-direct"` | 使用直连 DNS（绕过代理） |
| `"dns-remote"` | 使用远程 DNS（通过代理） |
| 自定义标签 | 引用 DNS 配置中定义的服务器 |

##### 域名解析策略

| 策略 | 说明 |
|------|------|
| `""` (空) | 不指定策略，使用默认行为 |
| `"prefer_ipv4"` | 优先使用 IPv4 地址 |
| `"prefer_ipv6"` | 优先使用 IPv6 地址 |
| `"ipv4_only"` | 仅使用 IPv4 地址 |
| `"ipv6_only"` | 仅使用 IPv6 地址 |
| `"prefer_ipv4"` | 优先 IPv4，失败时回退到 IPv6 |
| `"prefer_ipv6"` | 优先 IPv6，失败时回退到 IPv4 |

#### 返回值

| 类型 | 说明 |
|------|------|
| Map<String, Any> | domain_resolver 配置 |

**返回结构**:
```kotlin
// 基本配置（无 strategy）
mapOf("server" to "dns-direct")

// 完整配置（有 strategy）
mapOf(
    "server" to "dns-direct",
    "strategy" to "prefer_ipv4"
)
```

#### 行为说明

1. **测试模式** (`forTest = true`):
   - 总是忽略 `strategy` 参数
   - 仅返回 `server` 字段
   - 用于单元测试或配置测试

2. **生产模式** (`forTest = false`):
   - 如果 `strategy` 非空，包含 `strategy` 字段
   - 如果 `strategy` 为空，仅包含 `server` 字段

#### 示例

##### 示例 1: 基本用法
```kotlin
val resolver = buildDomainResolver()
// 结果: {"server": "dns-direct"}
```

##### 示例 2: 指定策略
```kotlin
val resolver = buildDomainResolver(
    strategy = "prefer_ipv4"
)
// 结果: {"server": "dns-direct", "strategy": "prefer_ipv4"}
```

##### 示例 3: 自定义 DNS 服务器
```kotlin
val resolver = buildDomainResolver(
    server = "custom-dns",
    strategy = "ipv6_only"
)
// 结果: {"server": "custom-dns", "strategy": "ipv6_only"}
```

##### 示例 4: 测试模式
```kotlin
val resolver = buildDomainResolver(
    strategy = "prefer_ipv4",
    forTest = true
)
// 结果: {"server": "dns-direct"}
// 注意: strategy 被忽略
```

#### 使用场景

##### 场景 1: Naive Outbound 域名解析

在 `ConfigBuilder.kt` 中使用：

```kotlin
if (bean is NaiveBean && 
    !proxyEntity.needExternal() && 
    !bean.serverAddress.isIpAddress()) {
    
    _hack_config_map["domain_resolver"] = buildDomainResolver(
        strategy = defaultServerDomainStrategy,
        forTest = forTest
    )
}
```

**条件**:
- 节点类型是 NaiveBean
- 不使用外部插件模式
- 服务器地址是域名（不是 IP）

**作用**:
- 确保域名在连接前被正确解析
- 使用直连 DNS 避免解析泄露
- 应用全局域名策略配置

##### 场景 2: 其他 Outbound 类型

```kotlin
// 为 Hysteria、TUIC 等其他 outbound 复用
if (!bean.serverAddress.isIpAddress()) {
    _hack_config_map["domain_resolver"] = buildDomainResolver(
        strategy = "ipv4_only"
    )
}
```

---

### parseNaive()

**包名**: `io.nekohasekai.sagernet.fmt.naive`

**签名**:
```kotlin
fun parseNaive(link: String): NaiveBean
```

**用途**: 解析 Naive 协议链接（naive+https://... 或 naive+quic://...）为 NaiveBean 对象。

#### 参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `link` | String | Naive 协议链接 |

#### 返回值

| 类型 | 说明 |
|------|------|
| NaiveBean | 解析后的配置对象 |

#### 链接格式

```
naive+<proto>://[<username>[:<password>]@]<host>[:<port>][?<params>][#<name>]
```

**组成部分**:
- `proto`: 协议类型，`https` 或 `quic`
- `username`: 可选，用户名
- `password`: 可选，密码
- `host`: 服务器地址（域名或 IP）
- `port`: 可选，端口号（默认 443）
- `params`: 可选，查询参数
  - `sni`: TLS SNI
  - `cert`: 证书内容（URL 编码）
  - `extra-headers`: 额外请求头（URL 编码）
  - `insecure-concurrency`: 并发连接数
- `name`: 可选，节点名称

#### 示例

##### 示例 1: 基本链接
```kotlin
val link = "naive+https://user:pass@proxy.example.com:443"
val bean = parseNaive(link)

// bean.proto == "https"
// bean.serverAddress == "proxy.example.com"
// bean.serverPort == 443
// bean.username == "user"
// bean.password == "pass"
```

##### 示例 2: QUIC 协议
```kotlin
val link = "naive+quic://user:pass@proxy.example.com:443"
val bean = parseNaive(link)

// bean.proto == "quic"
```

##### 示例 3: 带参数
```kotlin
val link = "naive+https://user:pass@proxy.example.com:443?sni=custom.sni.com&insecure-concurrency=5#MyNode"
val bean = parseNaive(link)

// bean.sni == "custom.sni.com"
// bean.insecureConcurrency == 5
// bean.name == "MyNode"
```

##### 示例 4: 带 Extra Headers
```kotlin
val headers = "User-Agent: MyApp\r\nX-ID: 123"
val encoded = URLEncoder.encode(headers, "UTF-8")
val link = "naive+https://user:pass@proxy.example.com:443?extra-headers=$encoded"
val bean = parseNaive(link)

// bean.extraHeaders == "User-Agent: MyApp\nX-ID: 123"
```

#### 异常

| 异常 | 条件 |
|------|------|
| IllegalArgumentException | 链接格式无效，无法解析为 URL |

---

### NaiveBean.toUri()

**包名**: `io.nekohasekai.sagernet.fmt.naive`

**签名**:
```kotlin
fun NaiveBean.toUri(proxyOnly: Boolean = false): String
```

**用途**: 将 NaiveBean 对象编码为 Naive 协议链接。

#### 参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `proxyOnly` | Boolean | `false` | 是否仅包含代理信息（不包含额外参数） |

#### 返回值

| 类型 | 说明 |
|------|------|
| String | Naive 协议链接 |

#### 行为说明

- `proxyOnly = false`: 包含所有配置（SNI、证书、extra headers 等）
- `proxyOnly = true`: 仅包含基本代理信息（服务器、端口、用户名、密码）

#### 示例

##### 示例 1: 完整链接
```kotlin
val bean = NaiveBean().apply {
    serverAddress = "proxy.example.com"
    serverPort = 443
    username = "user"
    password = "pass"
    proto = "https"
    sni = "custom.sni.com"
    name = "MyNode"
}

val link = bean.toUri()
// naive+https://user:pass@proxy.example.com:443?sni=custom.sni.com#MyNode
```

##### 示例 2: 仅代理信息
```kotlin
val link = bean.toUri(proxyOnly = true)
// https://user:pass@proxy.example.com:443
```

---

## 配置组合示例

### 完整工作流程

```kotlin
// 1. 创建用户配置
val bean = NaiveBean().apply {
    serverAddress = "proxy.example.com"
    serverPort = 443
    username = "user"
    password = "pass"
    proto = "quic"
    extraHeaders = "User-Agent: MyApp"
}

// 2. 转换为 sing-box 配置
val options = buildSingBoxOutboundNaiveBean(bean)

// 3. 添加 domain_resolver（如果需要）
if (!bean.serverAddress.isIpAddress()) {
    // 这部分通常在 ConfigBuilder 中处理
    val domainResolver = buildDomainResolver(
        strategy = "prefer_ipv4"
    )
    // 添加到完整配置中
}

// 4. 序列化为 JSON
val gson = GsonBuilder()
    .setPrettyPrinting()
    .create()
val json = gson.toJson(options)

// 5. 输出配置
println(json)
```

输出：
```json
{
  "type": "naive",
  "server": "proxy.example.com",
  "server_port": 443,
  "username": "user",
  "password": "pass",
  "quic": true,
  "extra_headers": {
    "User-Agent": ["MyApp"]
  },
  "tls": {
    "enabled": true,
    "server_name": "proxy.example.com"
  },
  "tag": "proxy"
}
```

---

## 类型映射表

### NaiveBean → Outbound_NaiveOptions

| NaiveBean 字段 | Outbound_NaiveOptions 字段 | 转换逻辑 |
|----------------|---------------------------|----------|
| `serverAddress` | `server` | 直接映射 |
| `serverPort` | `server_port` | 直接映射 |
| `username` | `username` | 非空时映射 |
| `password` | `password` | 非空时映射 |
| `proto = "quic"` | `quic = true` | 条件映射 |
| `proto = "https"` | (无对应字段) | 默认行为 |
| `sni` | `tls.server_name` | 映射到 TLS 配置 |
| `certificates` | `tls.certificate` | 映射到 TLS 配置 |
| `extraHeaders` | `extra_headers` | 解析为 Map |
| `insecureConcurrency` | `insecure_concurrency` | >0 时映射 |

---

## 常量

### 协议类型

| 常量值 | 说明 |
|--------|------|
| `"https"` | HTTPS 协议（默认） |
| `"quic"` | QUIC 协议 |

### DNS 服务器标签

| 常量值 | 说明 |
|--------|------|
| `"dns-direct"` | 直连 DNS |
| `"dns-remote"` | 远程 DNS |

### 域名解析策略

| 常量值 | 说明 |
|--------|------|
| `"prefer_ipv4"` | 优先 IPv4 |
| `"prefer_ipv6"` | 优先 IPv6 |
| `"ipv4_only"` | 仅 IPv4 |
| `"ipv6_only"` | 仅 IPv6 |

---

## 版本历史

### v1.4.2 (2026-06-13)

**新增**:
- ✅ `Outbound_NaiveOptions` 强类型配置类
- ✅ `buildDomainResolver()` 工具函数
- ✅ 完整的 KDoc 文档注释

**变更**:
- ✅ `buildSingBoxOutboundNaiveBean()` 从 Map-based 重构为强类型实现
- ✅ 移除 `JavaUtil.gson.toJson()` 手动序列化

**废弃**:
- ⚠️ 外部插件模式的 `buildNaiveConfig()` 函数（标记为 `@Deprecated`）

---

## 相关文档

- [架构设计文档](architecture-naive-outbound.md) - 完整的架构说明
- [迁移指南](migration-to-strong-types.md) - 从 Map-based 迁移到强类型
- [故障排查指南](architecture-naive-outbound.md#故障排查) - 常见问题解决

---

**文档版本**: 1.0  
**最后更新**: 2026-06-13  
**API 版本**: 1.4.2
