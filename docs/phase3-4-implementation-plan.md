# Phase 3-4 实施方案

## 概述

Phase 1-2 已完成代码清理和架构优化，所有功能测试通过。Phase 3-4 将聚焦于提升代码质量和文档完整性。

---

## Phase 3: 单元测试

### 📋 测试范围

#### 3.1 核心功能测试

##### 测试 3.1a: buildSingBoxOutboundNaiveBean() 函数
**目标**: 验证强类型配置生成的正确性

**测试用例**:
1. **基本配置测试**
   ```kotlin
   @Test
   fun testBasicNaiveConfig() {
       val bean = NaiveBean().apply {
           serverAddress = "example.com"
           serverPort = 443
           username = "user"
           password = "pass"
           proto = "https"
       }
       val result = buildSingBoxOutboundNaiveBean(bean)
       
       assertEquals("naive", result.type)
       assertEquals("example.com", result.server)
       assertEquals(443, result.server_port)
       assertEquals("user", result.username)
       assertEquals("pass", result.password)
       assertNotNull(result.tls)
       assertTrue(result.tls.enabled)
   }
   ```

2. **QUIC 协议测试**
   ```kotlin
   @Test
   fun testQuicProtocol() {
       val bean = NaiveBean().apply {
           serverAddress = "example.com"
           serverPort = 443
           proto = "quic"
       }
       val result = buildSingBoxOutboundNaiveBean(bean)
       
       assertEquals(true, result.quic)
   }
   ```

3. **Extra Headers 解析测试**
   ```kotlin
   @Test
   fun testExtraHeadersParsing() {
       val bean = NaiveBean().apply {
           serverAddress = "example.com"
           serverPort = 443
           extraHeaders = "User-Agent: CustomApp\nX-Test: value"
       }
       val result = buildSingBoxOutboundNaiveBean(bean)
       
       assertNotNull(result.extra_headers)
       assertEquals(2, result.extra_headers.size)
       assertEquals(listOf("CustomApp"), result.extra_headers["User-Agent"])
       assertEquals(listOf("value"), result.extra_headers["X-Test"])
   }
   ```

4. **TLS/SNI 配置测试**
   ```kotlin
   @Test
   fun testTlsConfiguration() {
       val bean = NaiveBean().apply {
           serverAddress = "example.com"
           serverPort = 443
           sni = "custom.sni.com"
           certificates = "cert-content"
       }
       val result = buildSingBoxOutboundNaiveBean(bean)
       
       assertNotNull(result.tls)
       assertTrue(result.tls.enabled)
       assertEquals("custom.sni.com", result.tls.server_name)
       assertEquals("cert-content", result.tls.certificate)
   }
   ```

5. **空值处理测试**
   ```kotlin
   @Test
   fun testEmptyFields() {
       val bean = NaiveBean().apply {
           serverAddress = "example.com"
           serverPort = 443
           username = ""
           password = ""
           extraHeaders = ""
       }
       val result = buildSingBoxOutboundNaiveBean(bean)
       
       assertNull(result.username)
       assertNull(result.password)
       assertNull(result.extra_headers)
   }
   ```

**预计工作量**: 2-3 小时

---

##### 测试 3.1b: buildDomainResolver() 工具函数
**目标**: 验证 domain_resolver 配置生成

**测试用例**:
1. **默认配置测试**
   ```kotlin
   @Test
   fun testDefaultDomainResolver() {
       val result = buildDomainResolver()
       
       assertEquals("dns-direct", result["server"])
       assertFalse(result.containsKey("strategy"))
   }
   ```

2. **带策略配置测试**
   ```kotlin
   @Test
   fun testDomainResolverWithStrategy() {
       val result = buildDomainResolver(
           strategy = "prefer_ipv4"
       )
       
       assertEquals("dns-direct", result["server"])
       assertEquals("prefer_ipv4", result["strategy"])
   }
   ```

3. **测试模式测试**
   ```kotlin
   @Test
   fun testDomainResolverForTest() {
       val result = buildDomainResolver(
           strategy = "prefer_ipv4",
           forTest = true
       )
       
       assertEquals("dns-direct", result["server"])
       assertFalse(result.containsKey("strategy"))
   }
   ```

4. **自定义服务器测试**
   ```kotlin
   @Test
   fun testCustomServer() {
       val result = buildDomainResolver(
           server = "custom-dns",
           strategy = "ipv6_only"
       )
       
       assertEquals("custom-dns", result["server"])
       assertEquals("ipv6_only", result["strategy"])
   }
   ```

**预计工作量**: 1 小时

---

##### 测试 3.1c: Outbound_NaiveOptions 序列化测试
**目标**: 验证强类型配置正确序列化为 JSON

**测试用例**:
1. **完整配置序列化测试**
   ```kotlin
   @Test
   fun testSerialization() {
       val options = Outbound_NaiveOptions().apply {
           type = "naive"
           server = "example.com"
           server_port = 443
           username = "user"
           password = "pass"
           quic = true
           tls = OutboundTLSOptions().apply {
               enabled = true
               server_name = "sni.example.com"
           }
       }
       
       val json = gson.toJson(options)
       val parsed = JsonParser.parseString(json).asJsonObject
       
       assertEquals("naive", parsed.get("type").asString)
       assertEquals("example.com", parsed.get("server").asString)
       assertEquals(443, parsed.get("server_port").asInt)
       assertTrue(parsed.get("quic").asBoolean)
       assertTrue(parsed.getAsJsonObject("tls").get("enabled").asBoolean)
   }
   ```

2. **空字段不序列化测试**
   ```kotlin
   @Test
   fun testNullFieldsNotSerialized() {
       val options = Outbound_NaiveOptions().apply {
           type = "naive"
           server = "example.com"
           server_port = 443
       }
       
       val json = gson.toJson(options)
       val parsed = JsonParser.parseString(json).asJsonObject
       
       assertFalse(parsed.has("username"))
       assertFalse(parsed.has("password"))
       assertFalse(parsed.has("quic"))
   }
   ```

**预计工作量**: 1-2 小时

---

#### 3.2 集成测试（可选）

##### 测试 3.2a: 配置生成端到端测试
**目标**: 验证从 NaiveBean 到最终 sing-box 配置的完整流程

**测试场景**:
1. 创建 NaiveBean
2. 调用 buildSingBoxOutboundNaiveBean()
3. 序列化为 JSON
4. 验证 JSON 结构符合 sing-box 规范

**预计工作量**: 1-2 小时

---

### 📊 Phase 3 总结

**总测试用例数**: 约 15-20 个  
**覆盖率目标**: ≥80%  
**预计总工作量**: 5-8 小时

**测试框架**:
- JUnit 4/5
- Mockito（如需 mock）
- AssertJ（流畅断言，可选）

**测试文件结构**:
```
app/src/test/java/io/nekohasekai/sagernet/fmt/naive/
├── NaiveFmtTest.kt                    # buildSingBoxOutboundNaiveBean() 测试
├── DomainResolverTest.kt              # buildDomainResolver() 测试
└── OutboundNaiveOptionsTest.kt        # 序列化测试
```

---

## Phase 4: 文档和代码注释

### 📝 文档范围

#### 4.1 开发者文档

##### 文档 4.1a: 架构说明文档
**文件**: `docs/architecture-naive-outbound.md`

**内容大纲**:
```markdown
# Naive Outbound 架构设计

## 概述
- Native Naive outbound 的设计目标
- 与外部插件模式的对比

## 架构图
- NaiveBean → buildSingBoxOutboundNaiveBean() → Outbound_NaiveOptions → JSON → sing-box

## 核心组件

### 1. 数据模型
- NaiveBean: 用户配置的数据结构
- Outbound_NaiveOptions: sing-box 配置的强类型表示

### 2. 配置构造
- buildSingBoxOutboundNaiveBean(): 转换函数
- 字段映射关系
- 特殊处理逻辑（extra_headers 解析、QUIC 标志等）

### 3. 工具函数
- buildDomainResolver(): domain_resolver 构造
- 参数说明和使用场景

## 配置示例

### 基本 HTTP/HTTPS 配置
...

### QUIC 配置
...

### 带 extra_headers 的配置
...

## 扩展指南
- 如何添加新字段
- 如何支持新协议
- 如何复用 buildDomainResolver()

## 故障排查
- 常见配置错误
- 日志分析方法
```

**预计工作量**: 2-3 小时

---

##### 文档 4.1b: API 参考文档
**文件**: `docs/api-naive-outbound.md`

**内容大纲**:
```markdown
# Naive Outbound API 参考

## 数据类

### NaiveBean
用户配置的数据模型

字段列表:
- serverAddress: String - 服务器地址
- serverPort: Int - 服务器端口
- username: String - 用户名
- password: String - 密码
- proto: String - 协议类型（https/quic）
- sni: String - TLS SNI
- certificates: String - 自定义证书
- extraHeaders: String - 额外 HTTP 头（多行文本）
- insecureConcurrency: Int - 并发连接数

### Outbound_NaiveOptions
sing-box 配置的强类型表示

字段列表:
- type: String - 固定为 "naive"
- server: String - 服务器地址
- server_port: Int - 服务器端口
- username: String? - 用户名（可选）
- password: String? - 密码（可选）
- quic: Boolean? - 是否启用 QUIC
- tls: OutboundTLSOptions - TLS 配置
- extra_headers: Map<String, List<String>>? - HTTP 头
- insecure_concurrency: Int? - 并发数
- ...

## 函数

### buildSingBoxOutboundNaiveBean()
将 NaiveBean 转换为 Outbound_NaiveOptions

参数:
- bean: NaiveBean - 用户配置

返回:
- Outbound_NaiveOptions - sing-box 配置

示例:
...

### buildDomainResolver()
构造 domain_resolver 配置

参数:
- server: String = "dns-direct" - DNS 服务器
- strategy: String = "" - 解析策略
- forTest: Boolean = false - 是否为测试模式

返回:
- Map<String, Any> - domain_resolver 配置

示例:
...
```

**预计工作量**: 1-2 小时

---

##### 文档 4.1c: 迁移指南
**文件**: `docs/migration-to-strong-types.md`

**内容大纲**:
```markdown
# 强类型配置迁移指南

## 背景
从 Map + JSON 迁移到强类型配置的原因和收益

## 迁移对比

### 之前（Map-based）
代码示例...

### 之后（Type-safe）
代码示例...

## 收益
- 编译期类型检查
- IDE 自动补全
- 重构安全
- 代码可读性

## 如何将其他 outbound 迁移到强类型
步骤指南...

## 常见问题
...
```

**预计工作量**: 1 小时

---

#### 4.2 代码注释增强

##### 注释 4.2a: Outbound_NaiveOptions 类注释
**文件**: `SingBoxOptions.java`

**增强内容**:
```java
/**
 * Type-safe configuration for sing-box naive outbound.
 * 
 * <p>This class represents the configuration structure for sing-box's native naive outbound
 * implementation. It provides compile-time type checking and IDE support compared to the
 * previous Map-based approach.
 * 
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * Outbound_NaiveOptions options = new Outbound_NaiveOptions();
 * options.server = "example.com";
 * options.server_port = 443;
 * options.username = "user";
 * options.password = "pass";
 * options.quic = true;
 * options.tls = new OutboundTLSOptions();
 * options.tls.enabled = true;
 * options.tls.server_name = "sni.example.com";
 * }</pre>
 * 
 * <h3>Field Mapping:</h3>
 * <ul>
 *   <li>{@code server} - Server hostname or IP address</li>
 *   <li>{@code server_port} - Server port number</li>
 *   <li>{@code username} - Authentication username (optional)</li>
 *   <li>{@code password} - Authentication password (optional)</li>
 *   <li>{@code quic} - Enable QUIC protocol (default: false)</li>
 *   <li>{@code tls} - TLS configuration (required)</li>
 *   <li>{@code extra_headers} - Additional HTTP headers (optional)</li>
 *   <li>{@code insecure_concurrency} - Concurrent connections (optional)</li>
 * </ul>
 * 
 * @see io.nekohasekai.sagernet.fmt.naive.buildSingBoxOutboundNaiveBean
 * @since 1.4.2
 */
public static class Outbound_NaiveOptions extends SingBoxOption {
    // 字段注释...
}
```

**预计工作量**: 30 分钟

---

##### 注释 4.2b: 函数文档增强
**文件**: `NaiveFmt.kt`

**已有**: buildDomainResolver() 已有完整 KDoc ✅

**需要增强**: buildSingBoxOutboundNaiveBean()
```kotlin
/**
 * Convert a NaiveBean configuration to a type-safe Outbound_NaiveOptions for sing-box.
 * 
 * This function performs the following transformations:
 * 1. Maps basic server configuration (address, port, credentials)
 * 2. Parses extra_headers from multi-line text to Map format
 * 3. Sets QUIC flag based on protocol type
 * 4. Constructs TLS configuration with SNI
 * 5. Handles optional fields (username, password, etc.)
 * 
 * @param bean The user-facing Naive configuration
 * @return A type-safe sing-box outbound configuration
 * 
 * @see Outbound_NaiveOptions
 * @see buildDomainResolver
 * 
 * @since 1.4.2 - Migrated from Map-based to type-safe implementation
 */
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): moe.matsuri.nb4a.SingBoxOptions.Outbound_NaiveOptions {
    // 实现...
}
```

**预计工作量**: 30 分钟

---

### 📊 Phase 4 总结

**文档产出**:
1. 架构说明文档（1 个）
2. API 参考文档（1 个）
3. 迁移指南（1 个）
4. 类注释增强（1 个类）
5. 函数注释增强（1 个函数）

**预计总工作量**: 4-6 小时

---

## 总体计划对比

### 方案 A: 完整执行 Phase 3 + Phase 4

**时间投入**: 9-14 小时  
**产出**:
- ✅ 15-20 个单元测试
- ✅ 3 份完整文档
- ✅ 完整的代码注释
- ✅ ≥80% 测试覆盖率

**优点**:
- 代码质量最高
- 文档最完整
- 未来维护性最好
- 适合长期项目

**缺点**:
- 时间投入较大
- 短期收益不明显

---

### 方案 B: 仅执行 Phase 3（单元测试）

**时间投入**: 5-8 小时  
**产出**:
- ✅ 15-20 个单元测试
- ✅ ≥80% 测试覆盖率

**优点**:
- 保证代码正确性
- 便于未来重构
- 时间投入中等

**缺点**:
- 缺少完整文档
- 新贡献者上手较慢

---

### 方案 C: 仅执行 Phase 4（文档）

**时间投入**: 4-6 小时  
**产出**:
- ✅ 3 份完整文档
- ✅ 完整的代码注释

**优点**:
- 文档完整
- 帮助理解架构
- 时间投入较小

**缺点**:
- 缺少测试保障
- 重构风险较高

---

### 方案 D: 精简版（核心测试 + 关键文档）

**时间投入**: 4-5 小时  
**产出**:
- ✅ 8-10 个核心单元测试（仅 3.1a, 3.1b）
- ✅ 1 份架构说明文档
- ✅ 函数注释增强

**优点**:
- 时间投入最小
- 覆盖核心功能
- 平衡质量和效率

**缺点**:
- 测试覆盖率较低（约 50-60%）
- 文档不够全面

---

### 方案 E: 跳过 Phase 3-4，直接合并

**时间投入**: 0 小时  
**产出**: 无

**优点**:
- 立即可用
- 无额外投入

**缺点**:
- 无测试保障
- 文档不完整
- 未来维护成本高

---

## 🎯 推荐方案

### 我的推荐: **方案 D（精简版）**

**理由**:
1. ✅ Phase 1-2 APK 测试已全部通过，功能已验证
2. ✅ 核心测试覆盖关键函数，防止未来回归
3. ✅ 架构文档帮助理解设计
4. ✅ 函数注释便于后续维护
5. ✅ 时间投入合理（4-5 小时）
6. ✅ 可以在 main 分支上继续完善其他测试和文档

**执行计划**:
1. **Phase 3 精简版**（3 小时）
   - 测试 3.1a: buildSingBoxOutboundNaiveBean()（6-8 个用例）
   - 测试 3.1b: buildDomainResolver()（4 个用例）

2. **Phase 4 精简版**（1.5 小时）
   - 文档 4.1a: 架构说明文档
   - 注释 4.2b: 函数文档增强

3. **提交和推送**（0.5 小时）
   - 提交测试代码
   - 提交文档
   - 推送到远程
   - 准备 PR

**总计**: 约 5 小时

---

## 📋 各方案对比表

| 方案 | 时间 | 测试 | 文档 | 注释 | 推荐度 |
|------|------|------|------|------|--------|
| A - 完整 | 9-14h | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| B - 仅测试 | 5-8h | ⭐⭐⭐⭐⭐ | ⭐ | ⭐ | ⭐⭐⭐ |
| C - 仅文档 | 4-6h | ⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| D - 精简 ⭐ | 4-5h | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| E - 跳过 | 0h | - | - | - | ⭐⭐⭐⭐ |

---

## ❓ 决策问题

请告诉我你想执行哪个方案：

- **A**: 完整执行 Phase 3 + Phase 4（9-14 小时，质量最高）
- **B**: 仅执行 Phase 3 单元测试（5-8 小时，保证正确性）
- **C**: 仅执行 Phase 4 文档（4-6 小时，便于理解）
- **D**: 精简版（4-5 小时，平衡质量和效率）⭐ 推荐
- **E**: 跳过，直接合并到 main（0 小时，立即可用）
- **自定义**: 你可以自己组合想要的部分

或者你也可以提出其他需求！
