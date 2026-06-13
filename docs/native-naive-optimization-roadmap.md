# Native Naive Outbound 优化开发路线图

## 项目概述

**目标**：完成从外部插件方案到 sing-box 原生 NaiveProxy outbound 的迁移优化

**当前状态**：✅ POC 验证成功
- GitHub Actions 构建通过（Run ID: 27461681905）
- NDK 28 + cronet-go 链接成功
- 真机测试正常（arm64-v8a，VPN/TUN 模式，链式代理）

**分支**：`feature/native-naive-poc`

---

## Phase 1: 代码清理 🔴 高优先级

### 任务 1.1: 移除已失效的插件残留代码

**状态**：📋 计划中

**问题描述**：
当前 APK 已不再使用外部 naive 插件（不打包 `libnaive.so`，不启动插件进程），但仓库中仍保留相关代码和文件，容易造成混淆。

**需要清理的文件/代码**：

1. **文件删除**：
   - `download_naive.sh` - 已不被 CI 调用
   - `matsuri_naive.so` - 本地测试残留
   - `test_naive.so` - 本地测试残留

2. **代码标注废弃**（暂不删除，作为回退路径）：
   - `app/src/main/java/io/nekohasekai/sagernet/fmt/naive/NaiveFmt.kt`
     - 函数 `buildNaiveConfig(port: Int): String`（line 98+）
     - 添加 `@Deprecated` 注解
   - `app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt`
     - 查找并标注 `buildNaiveConfig(port)` 调用逻辑

**实施步骤**：
```bash
# 1. 删除不再使用的文件
rm download_naive.sh matsuri_naive.so test_naive.so

# 2. 在 NaiveFmt.kt 中添加废弃注解
# 在 buildNaiveConfig() 函数上方添加：
@Deprecated(
    message = "External naive plugin is replaced by native sing-box outbound. Use buildSingBoxOutboundNaiveBean() instead.",
    level = DeprecationLevel.WARNING
)

# 3. 在 BoxInstance.kt 中标注相关逻辑
# 添加注释说明该代码路径已不会被执行
```

**验证方法**：
- 确认 IDE 中相关函数显示废弃警告
- 搜索代码确认没有活跃调用路径
- 本地构建并真机测试功能正常

**预计工作量**：1-2 小时

**交付物**：
- [ ] 删除 3 个文件的 commit
- [ ] 添加 `@Deprecated` 注解的 commit
- [ ] 更新相关代码注释

---

### 任务 1.2: 优化 NDK fallback 逻辑

**状态**：📋 计划中

**问题描述**：
当前 `buildScript/init/env_ndk.sh` 只检查 NDK 28，缺少优雅降级路径。虽然 CI workflow 中有 fallback 机制，但本地开发环境容错性不足。

**当前代码**（`buildScript/init/env_ndk.sh:24-27`）：
```bash
_NDK=$(ls -d "$ANDROID_HOME/ndk"/28.* 2>/dev/null | sort -V | tail -n 1)
if [ -z "$_NDK" ]; then
  echo "Error: NDK 28 not found. NDK 28 is required for native Naive/cronet-go POC."
  exit 1
fi
```

**改进方案**：
```bash
# Try NDK 28 first (required for cronet-go relocation 315 fix)
_NDK=$(ls -d "$ANDROID_HOME/ndk"/28.* 2>/dev/null | sort -V | tail -n 1)

# Fallback to NDK 27 if NDK 28 not available (may have linking issues)
if [ -z "$_NDK" ]; then
  echo "Warning: NDK 28 not found, falling back to NDK 27 (may have cronet-go linking issues)"
  _NDK=$(ls -d "$ANDROID_HOME/ndk"/27.* 2>/dev/null | sort -V | tail -n 1)
fi

if [ -z "$_NDK" ]; then
  echo "Error: NDK 28 or 27 not found. Native Naive outbound requires NDK 27+ for cronet-go compatibility."
  exit 1
fi
```

**实施步骤**：
1. 修改 `buildScript/init/env_ndk.sh`
2. 更新相关注释，说明 NDK 版本要求
3. 本地测试 NDK 27 和 28 环境

**验证方法**：
- NDK 28 环境：正常构建
- NDK 27 环境：显示警告但继续构建
- 无 NDK 环境：清晰错误提示

**预计工作量**：30 分钟

**交付物**：
- [ ] 修改 `env_ndk.sh` 的 commit
- [ ] 本地测试记录

---

## Phase 2: 架构优化 🟡 中优先级

### 任务 2.1: 将 Naive 配置改为强类型

**状态**：📋 计划中

**问题描述**：
当前 `buildSingBoxOutboundNaiveBean()` 使用 `mutableMapOf<String, Any>` 拼接 JSON，缺少编译期类型检查，容易出现字段拼写错误。

**当前实现**（`NaiveFmt.kt:59-92`）：
```kotlin
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): SingBoxOptions.SingBoxOption {
    val _hack_config_map = mutableMapOf<String, Any>()
    _hack_config_map["type"] = "naive"
    _hack_config_map["server"] = bean.serverAddress
    // ... 更多字段通过 map 拼接
    return SingBoxOptions.CustomSingBoxOption(JavaUtil.gson.toJson(_hack_config_map))
}
```

**目标架构**：
参考 `Outbound_HysteriaOptions` 和 `Outbound_TUICOptions` 的实现模式，定义强类型数据类。

**实施步骤**：

1. **定义数据类**（参考 `moe.matsuri.nb4a.SingBoxOptions`）：
```kotlin
// 在 SingBoxOptions.kt 或新文件中定义
data class Outbound_NaiveOptions(
    override val type: String = "naive",
    var server: String = "",
    var server_port: Int = 443,
    var username: String? = null,
    var password: String? = null,
    var quic: Boolean? = null,
    var insecure_concurrency: Int? = null,
    var extra_headers: Map<String, List<String>>? = null,
    var tls: OutboundTLSOptions? = null,
    var domain_resolver: DomainResolverOptions? = null
) : SingBoxOption()

data class DomainResolverOptions(
    var server: String = "dns-direct",
    var strategy: String? = null
)
```

2. **重写 `buildSingBoxOutboundNaiveBean()`**：
```kotlin
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): SingBoxOptions.Outbound_NaiveOptions {
    return SingBoxOptions.Outbound_NaiveOptions().apply {
        server = bean.serverAddress
        server_port = bean.serverPort
        
        if (bean.username.isNotBlank()) username = bean.username
        if (bean.password.isNotBlank()) password = bean.password
        if (bean.proto == "quic") quic = true
        if (bean.insecureConcurrency > 0) insecure_concurrency = bean.insecureConcurrency
        
        // extra_headers 处理
        if (bean.extraHeaders.isNotBlank()) {
            extra_headers = bean.extraHeaders.split("\n")
                .mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) parts[0].trim() to listOf(parts[1].trim())
                    else null
                }
                .toMap()
                .takeIf { it.isNotEmpty() }
        }
        
        // TLS 配置
        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.ifBlank { bean.serverAddress }
            if (bean.certificates.isNotBlank()) {
                certificate = bean.certificates
            }
        }
    }
}
```

3. **更新 `ConfigBuilder.kt` 中的调用**：
   - 查找所有使用 `buildSingBoxOutboundNaiveBean()` 的地方
   - 验证返回类型兼容性

**验证方法**：
- 单元测试：验证各字段映射正确性
- 集成测试：真机运行 naive+https 和 naive+quic 节点
- 对比测试：生成的 JSON 与原实现一致

**预计工作量**：3-4 小时

**交付物**：
- [ ] 新增数据类定义
- [ ] 重写 `buildSingBoxOutboundNaiveBean()`
- [ ] 单元测试代码
- [ ] 真机验证报告

---

### 任务 2.2: 抽取统一的 domain_resolver 构造逻辑

**状态**：📋 计划中

**问题描述**：
当前 `domain_resolver` 逻辑（`ConfigBuilder.kt:445-451`）只为 Naive 添加，且嵌入在主配置构建流程中，不利于代码复用。

**当前实现**：
```kotlin
if (bean is NaiveBean && !proxyEntity.needExternal() && !bean.serverAddress.isIpAddress()) {
    val domainResolver = mutableMapOf<String, Any>("server" to "dns-direct")
    if (!forTest && defaultServerDomainStrategy.isNotEmpty()) {
        domainResolver["strategy"] = defaultServerDomainStrategy
    }
    _hack_config_map["domain_resolver"] = domainResolver
}
```

**改进方案**：

1. **抽取为独立函数**（在 `ConfigBuilder.kt` 顶层或工具类中）：
```kotlin
/**
 * 构造 domain_resolver 配置
 * 
 * @param serverAddress 服务器地址
 * @param forTest 是否为测试模式
 * @param domainStrategy 域名解析策略（如 "prefer_ipv4"）
 * @return domain_resolver 配置，如果不需要则返回 null
 */
private fun buildDomainResolver(
    serverAddress: String,
    forTest: Boolean,
    domainStrategy: String
): Map<String, Any>? {
    // IP 地址不需要 domain_resolver
    if (serverAddress.isIpAddress()) return null
    
    val resolver = mutableMapOf<String, Any>("server" to "dns-direct")
    
    // 测试模式下不设置 strategy，使用默认值
    if (!forTest && domainStrategy.isNotEmpty()) {
        resolver["strategy"] = domainStrategy
    }
    
    return resolver
}
```

2. **在配置构建时使用**：
```kotlin
// 替换原有代码
if (bean is NaiveBean && !proxyEntity.needExternal()) {
    buildDomainResolver(
        bean.serverAddress,
        forTest,
        defaultServerDomainStrategy
    )?.let { _hack_config_map["domain_resolver"] = it }
}
```

3. **考虑扩展到其他 outbound**：
   - 评估其他 outbound（如 Hysteria、TUIC）是否需要类似逻辑
   - 统一 domain_resolver 的使用模式

**实施步骤**：
1. 抽取函数并添加文档注释
2. 替换现有调用点
3. 评估其他 outbound 的适用性
4. 添加单元测试

**验证方法**：
- 单元测试：覆盖 IP/域名、forTest/正常、有/无 strategy 的各种组合
- 集成测试：真机验证 DNS 解析行为
- 回归测试：确保其他 outbound 不受影响

**预计工作量**：1-2 小时

**交付物**：
- [ ] 新增 `buildDomainResolver()` 工具函数
- [ ] 重构 ConfigBuilder 调用
- [ ] 单元测试代码
- [ ] 其他 outbound 适用性评估报告

---

## Phase 3: 多架构支持 🟢 低优先级

### 任务 3.1: 支持 armeabi-v7a

**状态**：⏸️ 待 arm64-v8a 稳定后启动

**前置条件**：
- arm64-v8a 在 main 分支稳定运行至少 2 周
- 无重大 bug 报告
- 用户反馈良好

**问题描述**：
当前只支持 arm64-v8a（64 位 ARM），部分旧设备使用 armeabi-v7a（32 位 ARM）。

**潜在风险**：
- cronet-go 预编译库对 32 位 ARM 的支持未知
- 可能出现新的 native 链接问题
- APK 大小会显著增加（需打包两个架构的 so）

**实施步骤**：

1. **本地环境准备**：
   - 准备 armeabi-v7a 真机或模拟器
   - 配置 NDK 支持 armeabi-v7a 编译

2. **修改构建配置**：
   - `libcore/build.sh`：添加 `-target=armeabi-v7a` 支持
   - `app/build.gradle`：ABI split 配置

3. **构建验证**：
   - 本地构建 armeabi-v7a 版本
   - 检查 cronet-go 链接错误
   - 分析 so 文件大小

4. **真机测试**：
   - 安装并运行基础功能测试
   - 性能对比（32 位 vs 64 位）
   - 长时间稳定性测试

**验证方法**：
- 构建成功且无链接错误
- 32 位 ARM 真机可正常使用
- APK 大小增加在可接受范围内（< 20MB）

**预计工作量**：4-6 小时（含测试）

**交付物**：
- [ ] 支持 armeabi-v7a 的构建配置
- [ ] armeabi-v7a APK 构建产物
- [ ] 真机测试报告
- [ ] 性能对比数据

---

### 任务 3.2: 支持 x86/x86_64（可选）

**状态**：❄️ 冻结

**优先级**：非常低

**理由**：
- 真实 Android 设备几乎不使用 x86 架构
- 模拟器调试可使用 arm64-v8a（M1/M2 Mac，或开启 ARM 转换层的 x86 模拟器）
- cronet-go 对 x86 的支持未知，可能需要额外工作

**触发条件**：
- 明确的模拟器调试需求
- 或有用户报告 x86 设备兼容性问题

---

## Phase 4: 功能完善 🔵 按需

### 任务 4.1: 完善 Naive 特性支持

**状态**：📋 优先级降低

**当前已支持**：
- ✅ `naive+https` 基础连接
- ✅ `naive+quic` 协议（通过 `quic: true`）
- ✅ `extra-headers` 自定义头
- ✅ `insecure-concurrency` 并发控制
- ✅ 自定义 TLS `server_name`
- ✅ 自定义证书（通过 `certificate` 字段）

**待验证场景**（按需推进）：

1. **`udp_over_tcp` 功能**：
   - 当前代码未见明确处理
   - 需要确认 sing-box 原生 Naive 是否支持
   - 如支持，补充字段映射

2. **ECH (Encrypted Client Hello) 支持**：
   - sing-box 文档提到支持 `ech`
   - 需要补充 `NaiveBean` 字段
   - 需要 ECH 测试环境

3. **自定义证书路径 `certificate_path`**：
   - 当前只支持 `certificate`（证书内容）
   - 补充 `certificate_path`（证书文件路径）支持

**实施优先级**：
- 用户明确需求时再推进
- 或在真机测试中发现缺失功能

**已知限制**（不予支持）：
- ❌ uTLS fingerprint
- ❌ TLS fragment
- ❌ Reality protocol
- ❌ 其他 sing-box 原生 Naive 不支持的高级 TLS 特性

**预计工作量**：按需评估

---

## 时间规划

### Week 1-2: 代码清理与优化
- **Day 1** (2h)：执行任务 1.1 - 移除插件残留代码
- **Day 2** (0.5h)：执行任务 1.2 - 优化 NDK fallback
- **Day 3-4** (4h)：执行任务 2.1 - 强类型配置
- **Day 5** (2h)：执行任务 2.2 - domain_resolver 重构
- **Day 6** (2h)：代码 review 和修复
- **Day 7** (2h)：更新文档，准备 PR

**里程碑**：完成所有 Phase 1 和 Phase 2 任务

### Week 3-4: 稳定性观察
- 继续在 feature 分支真机测试
- 收集用户反馈（如有 beta 测试者）
- 监控崩溃日志
- 性能指标对比

**里程碑**：无重大 bug，性能表现符合预期

### Week 5: 合并到 main
- 创建 PR：`feature/native-naive-poc` → `main`
- PR 内容：详细说明改动、验证结果、风险评估
- 合并后发布 beta/preview 版本
- 继续监控线上表现

**里程碑**：成功合并到 main 分支

### 未来（按需）: 多架构支持
- 等待 main 分支稳定 2 周+
- 评估用户对 armeabi-v7a 的需求
- 如有需求，开启任务 3.1

---

## 成功指标

### 技术指标
- ✅ CI 构建成功率 100%
- ✅ libcore 构建时间 < 15 分钟
- ✅ APK 大小增加 < 10MB（相比插件方案）
- ✅ 真机崩溃率 < 0.1%
- ✅ 连接成功率 > 99%

### 代码质量指标
- ✅ 无 `@Deprecated` 代码被活跃调用
- ✅ Kotlin 编译警告 = 0
- ✅ 代码覆盖率（核心逻辑）> 60%
- ✅ 代码 review 通过

### 用户体验指标
- ✅ 配置迁移无感知
- ✅ 连接速度无明显下降
- ✅ 内存占用减少（相比插件方案）
- ✅ 用户反馈正面

---

## 风险管理

### 已缓解的风险 ✅
- ~~NDK 28 链接失败~~ → 已验证通过
- ~~CI 构建失败~~ → 已稳定运行
- ~~真机运行异常~~ → 已测试正常

### 待观察的风险 ⚠️
- **用户配置兼容性**：虽然理论上无缝迁移，但需要真实用户验证
  - 缓解措施：保留插件回退路径，出问题可快速回滚
- **长期稳定性**：当前测试时间有限
  - 缓解措施：分阶段发布（feature → beta → stable）

### 可接受的风险 ✔️
- **TLS 功能受限**：接受 sing-box 原生 Naive 的限制
- **多架构支持延后**：优先保证 arm64-v8a 稳定

---

## 参考资料

### 相关文档
- [native-naive-outbound-evaluation.md](./native-naive-outbound-evaluation.md) - POC 评估文档
- [sing-box NaiveProxy outbound](https://sing-box.sagernet.org/configuration/outbound/naive/) - 官方文档

### 关键提交
- `b253c29` - Add domain resolver for native Naive outbound
- `9a5c9cb` - Try NDK 28 for native Naive linking
- `6eb8af3` - Enable native Naive outbound POC
- `768d254` - Document native Naive outbound evaluation

### CI 构建记录
- Run ID: 27461681905 (2026-06-13 08:29 UTC)
- Status: ✅ Success
- Duration: Native Build 10s, APK Build 3m47s

---

## 更新日志

### 2026-06-13
- 创建优化路线图文档
- 完成 POC 验证状态确认
- 规划 Phase 1-4 任务
- 设定时间表和成功指标
