# Naive Outbound 架构设计

## 概述

Native Naive outbound 是 NekoBox 中实现的基于 sing-box 原生 naive 协议支持的出站代理功能。相比之前的外部插件模式，原生实现具有更好的性能、更低的资源占用和更简单的部署方式。

### 设计目标

1. **原生集成**: 直接使用 sing-box 的内置 naive outbound，无需外部二进制
2. **类型安全**: 使用强类型配置类替代 Map + JSON 的临时方案
3. **易于维护**: 清晰的代码结构，完整的文档注释
4. **性能优化**: 减少序列化开销，提升配置生成效率
5. **向后兼容**: 保持与现有配置格式的兼容性

### 与外部插件模式的对比

| 维度 | 外部插件模式 | Native 原生模式 |
|------|-------------|-----------------|
| **部署** | 需要独立的 naive-plugin APK | 内置于主应用 |
| **资源占用** | 独立进程，额外内存开销 | 共享进程，资源高效 |
| **维护成本** | 需要同步维护插件和主应用 | 统一维护 |
| **配置复杂度** | 需要 host-resolver-rules 映射 | 直接配置 domain_resolver |
| **性能** | 进程间通信开销 | 进程内调用，性能更好 |
| **用户体验** | 需要额外安装插件 | 开箱即用 |

---

## 架构图

### 数据流转图

```
┌─────────────┐
│  NaiveBean  │  用户配置（UI 层数据模型）
└──────┬──────┘
       │
       │ buildSingBoxOutboundNaiveBean()
       ▼
┌──────────────────────────┐
│ Outbound_NaiveOptions    │  强类型 sing-box 配置
└──────┬───────────────────┘
       │
       │ Gson 序列化
       ▼
┌──────────────────────────┐
│  JSON Configuration      │  sing-box 配置 JSON
└──────┬───────────────────┘
       │
       │ libcore
       ▼
┌──────────────────────────┐
│  sing-box Runtime        │  实际运行的 naive 出站
└──────────────────────────┘
```

### 组件关系图

```
┌─────────────────────────────────────────────────────────┐
│                    NaiveFmt.kt                          │
│                                                         │
│  ┌────────────────────────────────────────────┐       │
│  │  buildSingBoxOutboundNaiveBean()           │       │
│  │  - 字段映射                                 │       │
│  │  - extra_headers 解析                      │       │
│  │  - QUIC 标志处理                           │       │
│  │  - TLS 配置构造                            │       │
│  └────────────────────────────────────────────┘       │
│                                                         │
│  ┌────────────────────────────────────────────┐       │
│  │  buildDomainResolver()                     │       │
│  │  - DNS 服务器配置                          │       │
│  │  - 解析策略设置                            │       │
│  └────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                  SingBoxOptions.java                    │
│                                                         │
│  ┌────────────────────────────────────────────┐       │
│  │  Outbound_NaiveOptions                     │       │
│  │  - type: String                            │       │
│  │  - server: String                          │       │
│  │  - server_port: Int                        │       │
│  │  - username: String?                       │       │
│  │  - password: String?                       │       │
│  │  - quic: Boolean?                          │       │
│  │  - tls: OutboundTLSOptions                 │       │
│  │  - extra_headers: Map<String, List<String>>│       │
│  │  - ...                                     │       │
│  └────────────────────────────────────────────┘       │
│                                                         │
│  ┌────────────────────────────────────────────┐       │
│  │  OutboundTLSOptions                        │       │
│  │  - enabled: Boolean                        │       │
│  │  - server_name: String                     │       │
│  │  - certificate: String                     │       │
│  │  - ...                                     │       │
│  └────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────┘
```

---

## 核心组件

### 1. 数据模型

#### 1.1 NaiveBean

**位置**: `io.nekohasekai.sagernet.fmt.naive.NaiveBean`

**用途**: 表示用户在 UI 中配置的 Naive 节点信息

**关键字段**:
```kotlin
class NaiveBean : AbstractBean() {
    var serverAddress: String = ""    // 服务器地址（域名或 IP）
    var serverPort: Int = 443          // 服务器端口
    var username: String = ""          // 用户名
    var password: String = ""          // 密码
    var proto: String = "https"        // 协议类型：https, quic
    var sni: String = ""               // TLS SNI（可选）
    var certificates: String = ""      // 自定义证书（可选）
    var extraHeaders: String = ""      // 额外 HTTP 头（多行文本）
    var insecureConcurrency: Int = 0   // 并发连接数（可选）
}
```

**特点**:
- UI 层数据模型，字段名直观易懂
- 支持多行文本格式（extraHeaders）
- 包含协议类型（proto）用于判断 QUIC

#### 1.2 Outbound_NaiveOptions

**位置**: `moe.matsuri.nb4a.SingBoxOptions.Outbound_NaiveOptions`

**用途**: 强类型表示 sing-box 的 naive outbound 配置

**关键字段**:
```java
public static class Outbound_NaiveOptions extends SingBoxOption {
    @SerializedName("type")
    public String type = "naive";              // 固定为 "naive"
    
    // ServerOptions
    public String server;                      // 服务器地址
    public Integer server_port;                // 服务器端口
    
    // Authentication
    public String username;                    // 用户名（可选）
    public String password;                    // 密码（可选）
    
    // Protocol
    public String network;                     // 网络类型（可选）
    public Boolean quic;                       // 启用 QUIC
    
    // TLS
    public OutboundTLSOptions tls;            // TLS 配置
    
    // Naive-specific
    public Integer insecure_concurrency;       // 并发连接数
    public Map<String, List<String>> extra_headers; // HTTP 头
    
    // DialerOptions
    public String detour;                      // 分流标签
    public String bind_interface;              // 绑定网络接口
    public String domain_strategy;             // 域名策略
    // ... 更多 dialer 选项
}
```

**特点**:
- 完全符合 sing-box 配置规范
- 字段使用 snake_case 命名（sing-box 约定）
- 可选字段使用包装类型（Integer, Boolean）
- 继承自 `SingBoxOption` 以支持序列化

---

### 2. 配置构造

#### 2.1 buildSingBoxOutboundNaiveBean()

**位置**: `io.nekohasekai.sagernet.fmt.naive.NaiveFmt.kt`

**签名**:
```kotlin
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): Outbound_NaiveOptions
```

**功能**: 将用户配置（NaiveBean）转换为 sing-box 配置（Outbound_NaiveOptions）

**转换逻辑**:

##### 2.1.1 基本字段映射
```kotlin
return Outbound_NaiveOptions().apply {
    type = "naive"
    server = bean.serverAddress
    server_port = bean.serverPort
    
    if (bean.username.isNotBlank()) username = bean.username
    if (bean.password.isNotBlank()) password = bean.password
}
```

**说明**:
- 直接映射基本字段
- 空白字段不设置（保持 null，序列化时自动忽略）

##### 2.1.2 QUIC 协议处理
```kotlin
if (bean.proto == "quic") quic = true
```

**说明**:
- 根据 `proto` 字段判断是否启用 QUIC
- 仅当协议为 "quic" 时设置 `quic = true`
- HTTPS 协议不设置此字段（默认 null）

##### 2.1.3 Extra Headers 解析
```kotlin
if (bean.extraHeaders.isNotBlank()) {
    val headers = mutableMapOf<String, List<String>>()
    bean.extraHeaders.split("\n").forEach { line ->
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) {
            headers[parts[0].trim()] = listOf(parts[1].trim())
        }
    }
    if (headers.isNotEmpty()) {
        extra_headers = headers
    }
}
```

**输入格式** (多行文本):
```
User-Agent: CustomApp/1.0
X-Custom-Header: custom-value
Authorization: Bearer token123
```

**输出格式** (Map):
```json
{
  "User-Agent": ["CustomApp/1.0"],
  "X-Custom-Header": ["custom-value"],
  "Authorization": ["Bearer token123"]
}
```

**处理规则**:
- 按换行符分割
- 每行按第一个 `:` 分割为键值对
- 键和值自动 trim 空格
- 忽略格式错误的行
- 空 headers 不设置字段

##### 2.1.4 TLS 配置构造
```kotlin
tls = OutboundTLSOptions().apply {
    enabled = true
    server_name = bean.sni.ifBlank { bean.serverAddress }
    if (bean.certificates.isNotBlank()) {
        certificate = bean.certificates
    }
}
```

**说明**:
- TLS 总是启用（naive 协议要求）
- SNI 默认使用服务器地址，可自定义覆盖
- 自定义证书为可选字段

##### 2.1.5 并发连接数
```kotlin
if (bean.insecureConcurrency > 0) {
    insecure_concurrency = bean.insecureConcurrency
}
```

**说明**:
- 仅当大于 0 时设置
- 用于提升多连接场景性能

---

### 3. 工具函数

#### 3.1 buildDomainResolver()

**位置**: `io.nekohasekai.sagernet.fmt.naive.NaiveFmt.kt`

**签名**:
```kotlin
fun buildDomainResolver(
    server: String = "dns-direct",
    strategy: String = "",
    forTest: Boolean = false
): Map<String, Any>
```

**功能**: 构造 domain_resolver 配置，用于域名解析

**参数说明**:
- `server`: DNS 服务器标签，默认 "dns-direct"（使用直连 DNS）
- `strategy`: 域名解析策略，如 "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"
- `forTest`: 是否为测试模式，测试时跳过 strategy 配置

**返回值**:
```kotlin
// 基本配置
mapOf("server" to "dns-direct")

// 带策略配置
mapOf(
    "server" to "dns-direct",
    "strategy" to "prefer_ipv4"
)
```

**使用场景**:

##### 场景 1: Naive Outbound 域名解析
```kotlin
if (bean is NaiveBean && !bean.serverAddress.isIpAddress()) {
    _hack_config_map["domain_resolver"] = buildDomainResolver(
        strategy = defaultServerDomainStrategy,
        forTest = forTest
    )
}
```

**作用**:
- 当服务器地址为域名时，需要配置 domain_resolver
- 确保域名解析使用正确的 DNS 服务器和策略
- 避免在代理内解析导致的泄露问题

##### 场景 2: 其他 Outbound 类型复用
```kotlin
// 未来可以为其他需要域名解析的 outbound 复用
_hack_config_map["domain_resolver"] = buildDomainResolver(
    server = "custom-dns",
    strategy = "ipv6_only"
)
```

**设计优势**:
- 函数封装，逻辑集中
- 参数化配置，灵活适配不同场景
- 默认值合理，简化常见用法
- 可复用，便于其他 outbound 采用

---

## 配置示例

### 示例 1: 基本 HTTPS 配置

**用户配置** (NaiveBean):
```kotlin
NaiveBean().apply {
    serverAddress = "proxy.example.com"
    serverPort = 443
    username = "myuser"
    password = "mypass"
    proto = "https"
}
```

**生成的 sing-box 配置**:
```json
{
  "type": "naive",
  "server": "proxy.example.com",
  "server_port": 443,
  "username": "myuser",
  "password": "mypass",
  "tls": {
    "enabled": true,
    "server_name": "proxy.example.com"
  },
  "domain_resolver": {
    "server": "dns-direct",
    "strategy": "prefer_ipv4"
  },
  "tag": "proxy"
}
```

---

### 示例 2: QUIC 协议配置

**用户配置**:
```kotlin
NaiveBean().apply {
    serverAddress = "quic.example.com"
    serverPort = 443
    username = "myuser"
    password = "mypass"
    proto = "quic"              // 指定 QUIC 协议
}
```

**生成的配置**:
```json
{
  "type": "naive",
  "server": "quic.example.com",
  "server_port": 443,
  "username": "myuser",
  "password": "mypass",
  "quic": true,                 // QUIC 标志
  "tls": {
    "enabled": true,
    "server_name": "quic.example.com"
  },
  "domain_resolver": {
    "server": "dns-direct",
    "strategy": "prefer_ipv4"
  },
  "tag": "proxy"
}
```

---

### 示例 3: 自定义 SNI 和证书

**用户配置**:
```kotlin
NaiveBean().apply {
    serverAddress = "123.45.67.89"
    serverPort = 443
    username = "myuser"
    password = "mypass"
    proto = "https"
    sni = "custom.sni.com"      // 自定义 SNI
    certificates = """
        -----BEGIN CERTIFICATE-----
        MIIBkTCB+wIJAKHHCgVZU...
        -----END CERTIFICATE-----
    """.trimIndent()
}
```

**生成的配置**:
```json
{
  "type": "naive",
  "server": "123.45.67.89",
  "server_port": 443,
  "username": "myuser",
  "password": "mypass",
  "tls": {
    "enabled": true,
    "server_name": "custom.sni.com",
    "certificate": "-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJAKHHCgVZU...\n-----END CERTIFICATE-----"
  },
  "tag": "proxy"
}
```

**说明**: 服务器地址为 IP 时，不生成 domain_resolver

---

### 示例 4: 带 Extra Headers

**用户配置**:
```kotlin
NaiveBean().apply {
    serverAddress = "proxy.example.com"
    serverPort = 443
    username = "myuser"
    password = "mypass"
    proto = "https"
    extraHeaders = """
        User-Agent: MyApp/1.0
        X-Custom-ID: 12345
        Authorization: Bearer secrettoken
    """.trimIndent()
}
```

**生成的配置**:
```json
{
  "type": "naive",
  "server": "proxy.example.com",
  "server_port": 443,
  "username": "myuser",
  "password": "mypass",
  "extra_headers": {
    "User-Agent": ["MyApp/1.0"],
    "X-Custom-ID": ["12345"],
    "Authorization": ["Bearer secrettoken"]
  },
  "tls": {
    "enabled": true,
    "server_name": "proxy.example.com"
  },
  "domain_resolver": {
    "server": "dns-direct",
    "strategy": "prefer_ipv4"
  },
  "tag": "proxy"
}
```

---

### 示例 5: 高并发配置

**用户配置**:
```kotlin
NaiveBean().apply {
    serverAddress = "fast.example.com"
    serverPort = 443
    username = "myuser"
    password = "mypass"
    proto = "https"
    insecureConcurrency = 10    // 允许 10 个并发连接
}
```

**生成的配置**:
```json
{
  "type": "naive",
  "server": "fast.example.com",
  "server_port": 443,
  "username": "myuser",
  "password": "mypass",
  "insecure_concurrency": 10,
  "tls": {
    "enabled": true,
    "server_name": "fast.example.com"
  },
  "domain_resolver": {
    "server": "dns-direct",
    "strategy": "prefer_ipv4"
  },
  "tag": "proxy"
}
```

**注意**: `insecure_concurrency` 会降低安全性，仅在性能要求极高时使用

---

## 扩展指南

### 如何添加新字段

假设 sing-box 新增了一个 `timeout` 字段用于设置连接超时。

#### 步骤 1: 更新数据类

**SingBoxOptions.java**:
```java
public static class Outbound_NaiveOptions extends SingBoxOption {
    // ... 现有字段
    
    public String timeout;  // 新增字段
}
```

#### 步骤 2: 更新 NaiveBean（如果需要 UI 配置）

**NaiveBean.kt**:
```kotlin
class NaiveBean : AbstractBean() {
    // ... 现有字段
    
    var timeout: String = ""  // 新增字段
}
```

#### 步骤 3: 更新转换函数

**NaiveFmt.kt**:
```kotlin
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): Outbound_NaiveOptions {
    return Outbound_NaiveOptions().apply {
        // ... 现有映射
        
        if (bean.timeout.isNotBlank()) timeout = bean.timeout
    }
}
```

#### 步骤 4: 测试

1. 编译确认无错误
2. 创建包含新字段的配置
3. 检查生成的 JSON 包含该字段
4. 测试连接功能正常

---

### 如何支持新协议

假设要添加对 `naive+http` 协议的支持（未加密的 HTTP）。

#### 步骤 1: 扩展协议判断

**NaiveFmt.kt**:
```kotlin
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): Outbound_NaiveOptions {
    return Outbound_NaiveOptions().apply {
        // ...
        
        // 根据协议类型设置 network
        when (bean.proto) {
            "quic" -> quic = true
            "http" -> network = "tcp"  // HTTP 使用 TCP
            "https" -> {
                // 默认行为，无需特殊设置
            }
        }
        
        // TLS 配置（仅 HTTPS 和 QUIC 需要）
        if (bean.proto != "http") {
            tls = OutboundTLSOptions().apply {
                enabled = true
                server_name = bean.sni.ifBlank { bean.serverAddress }
                if (bean.certificates.isNotBlank()) {
                    certificate = bean.certificates
                }
            }
        }
    }
}
```

#### 步骤 2: 更新协议解析

**NaiveFmt.kt** (parseNaive 函数):
```kotlin
fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    // proto 现在可以是 "https", "quic", 或 "http"
    // ...
}
```

#### 步骤 3: UI 更新（可选）

在节点编辑页面添加协议选择器：
- HTTP (不安全)
- HTTPS
- QUIC

---

### 如何复用 buildDomainResolver()

假设你正在实现一个新的 outbound 类型（例如 Hysteria），也需要域名解析。

**ConfigBuilder.kt**:
```kotlin
import io.nekohasekai.sagernet.fmt.naive.buildDomainResolver

// 在构造 Hysteria outbound 时
if (bean is HysteriaBean && !bean.serverAddress.isIpAddress()) {
    _hack_config_map["domain_resolver"] = buildDomainResolver(
        strategy = defaultServerDomainStrategy,
        forTest = forTest
    )
}
```

**优势**:
- 无需重复编写逻辑
- 配置格式统一
- 便于维护和修改

---

## 故障排查

### 常见问题 1: 连接失败

**症状**: Naive 节点无法连接，日志显示超时或拒绝连接

**排查步骤**:
1. 检查配置 JSON 是否正确生成
   ```
   grep -A 20 '"type": "naive"' /path/to/log
   ```

2. 验证必需字段
   - `server` 和 `server_port` 是否正确
   - `username` 和 `password` 是否设置
   - `tls.enabled` 是否为 true

3. 检查网络连接
   - 服务器地址是否可达
   - 端口是否开放
   - 防火墙规则

4. 验证域名解析（如果服务器是域名）
   - 检查是否有 `domain_resolver` 配置
   - 尝试手动解析域名

---

### 常见问题 2: Extra Headers 不生效

**症状**: 配置了 extra_headers 但服务器未收到

**排查步骤**:
1. 检查输入格式
   ```
   正确: "Header-Name: value"
   错误: "Header-Name = value"  (使用了 = 而不是 :)
   错误: "Header-Name:value"     (缺少空格也可以，但建议加空格)
   ```

2. 检查生成的 JSON
   ```json
   "extra_headers": {
     "Header-Name": ["value"]
   }
   ```

3. 查看运行时日志
   ```
   grep -i "extra_headers" /path/to/log
   ```

---

### 常见问题 3: QUIC 协议不工作

**症状**: 设置了 QUIC 但仍使用 TCP 连接

**排查步骤**:
1. 确认 `proto` 字段设置正确
   ```kotlin
   bean.proto = "quic"  // 必须明确设置
   ```

2. 检查生成的配置
   ```json
   {
     "type": "naive",
     "quic": true,  // 必须存在且为 true
     ...
   }
   ```

3. 验证服务器支持
   - 服务器端是否启用 QUIC
   - UDP 端口是否开放
   - 防火墙是否允许 UDP

4. 查看协议协商日志
   ```
   grep -i "quic" /path/to/log
   # 应该看到 "protocol: quic/1+spdy/3"
   ```

---

### 常见问题 4: TLS 握手失败

**症状**: 连接时出现证书错误或 TLS 握手失败

**排查步骤**:
1. 检查 SNI 配置
   ```json
   "tls": {
     "server_name": "correct.sni.com"  // 必须与证书匹配
   }
   ```

2. 如果使用自定义证书，验证证书内容
   ```kotlin
   bean.certificates = """
     -----BEGIN CERTIFICATE-----
     完整的证书内容
     -----END CERTIFICATE-----
   """
   ```

3. 检查证书有效期和信任链

4. 尝试禁用证书验证（仅用于调试）
   ```java
   tls.insecure = true  // 不要在生产环境使用
   ```

---

### 日志分析方法

#### 1. 查找 Naive 配置
```bash
grep -B 5 -A 15 '"type": "naive"' logfile.log
```

#### 2. 查找连接日志
```bash
grep "outbound/naive" logfile.log
```

关键日志示例：
```
INFO outbound/naive[proxy]: NaiveProxy started, version: 147.0.7727.49
DEBUG outbound/naive[proxy]: open TCP connection to 1.2.3.4:443
DEBUG outbound/naive[proxy]: response received, protocol: h2, status: 200
```

#### 3. 查找错误信息
```bash
grep -i "error\|failed\|timeout" logfile.log | grep naive
```

#### 4. 查找域名解析
```bash
grep "domain_resolver" logfile.log
```

#### 5. 查找 QUIC 协议
```bash
grep -i "quic" logfile.log
```

---

## 参考资料

### sing-box 官方文档
- [Naive Outbound 配置](https://sing-box.sagernet.org/configuration/outbound/naive/)
- [TLS 配置选项](https://sing-box.sagernet.org/configuration/shared/tls/)
- [DNS 配置](https://sing-box.sagernet.org/configuration/dns/)

### 相关源码
- `app/src/main/java/io/nekohasekai/sagernet/fmt/naive/NaiveFmt.kt`
- `app/src/main/java/io/nekohasekai/sagernet/fmt/naive/NaiveBean.kt`
- `app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java`
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt`

### 相关文档
- `docs/phase2-architecture-optimization-completed.md` - Phase 2 完成报告
- `docs/phase2-apk-test-report.md` - APK 测试报告
- `docs/native-naive-optimization-roadmap.md` - 完整开发路线图

---

**文档版本**: 1.0  
**最后更新**: 2026-06-13  
**维护者**: NekoBox Development Team
