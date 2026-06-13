# sing-box 升级与功能移植指南

> **文档目的**: 总结 ThBox/NekoBox 项目从 sing-box 1.12.x 升级到 1.13.x 的完整经验，为后续 sing-box 新版本升级提供可复用的知识库和操作手册。
>
> **适用场景**: sing-box 发布新版本（1.14+）时，快速评估可行性、规避已知陷阱、复用验证流程。
>
> **文档日期**: 2026-06-13
> **基于实战**: ThBox 1.13.x 升级项目（2026-05-31 实施完成）

---

## 📋 目录

1. [核心经验总结](#核心经验总结)
2. [升级可行性评估方法](#升级可行性评估方法)
3. [技术架构关键点](#技术架构关键点)
4. [完整升级流程](#完整升级流程)
5. [常见陷阱与解决方案](#常见陷阱与解决方案)
6. [验证测试方法](#验证测试方法)
7. [附录：工具与命令](#附录工具与命令)

---

## 核心经验总结

### 🎯 关键发现

1. **配置兼容性比预期好得多**
   - MatsuriDayo 的 sing-box 魔改 **不含任何自定义 option 字段**
   - `option/` 包与官方逐字节完全相同
   - 魔改仅约 250 行桥接代码 + 7 个小 patch
   - ✅ **结论**: 配置层迁移成本几乎为零

2. **真正的升级难点**（按难度排序）
   | 难点 | 说明 | 难度 |
   |------|------|------|
   | `sing` 基础库升级 | API 变动会波及 boxapi 与 libcore | 🟡 中 |
   | `gomobile-matsuri` 工具链 | 需跟随 Go 版本升级 | 🔴 中-高 |
   | libcore 架构适配 | 注册表重构（endpoint/service） | 🟡 中 |
   | 重新 apply patch | 机械但位置可能位移 | 🟢 低-中 |
   | ConfigBuilder 现代化 | 仅上 1.14+ 强制 | 🟡 中-高 |

3. **构建 vs 运行时验证**
   - ⚠️ 构建通过 ≠ 运行正常
   - 1.13 升级遇到 3 个运行时阻断（构建全绿）：
     - Legacy inbound 字段（sniff/domain_strategy）
     - TUN 地址字段（inet4/6_address）
     - Netlink ban（未注册 PlatformInterface）
   - ✅ **教训**: 必须真机测试，不能只看 CI 绿色

4. **拆分关注点原则**
   - ❌ 不要同时做：版本升级 + 高风险新特性
   - ✅ 先稳定基线，再增量添加功能
   - 实例：1.13 升级与原生 NaiveProxy 分开处理

5. **外部依赖是最大变数**
   - 原生 NaiveProxy（cronet-go）链接失败
   - NDK 版本、gomobile 工具链兼容性
   - ✅ **策略**: 优先用外部插件方案，降低构建风险

---

## 升级可行性评估方法

### 步骤 1: 对比魔改差异

```bash
# 1. 克隆三方仓库到干净目录
git clone --depth 1 -b <matsuri-branch> https://github.com/MatsuriDayo/sing-box matsuri-singbox
git clone --depth 1 -b <official-tag> https://github.com/SagerNet/sing-box official-singbox

# 2. 确认魔改版基线版本
grep 'Version =' matsuri-singbox/constant/version.go

# 3. 找出自定义新增文件
diff -rq matsuri-singbox official-singbox | grep -i 'only in' | grep matsuri-singbox

# 4. 找出被修改的文件
diff -rq matsuri-singbox official-singbox | grep -i 'differ'

# 5. 关键验证：option/ 包是否有差异
diff -rq matsuri-singbox/option official-singbox/option
```

### 步骤 2: 识别必需的 patch

**已知必需 patch（1.12 → 1.13 经验）**:

| 文件 | 改动 | 原因 |
|------|------|------|
| `common/dialer/default.go` | 加 `DoNotSelectInterface` 全局开关 | VPN 接口由 Android 接管 |
| `protocol/vless/outbound.go` | multiplex 时清空 flow | 兼容性修复 |
| `protocol/tun/inbound.go` | 调用 gvisor 关闭修复 | 稳定性（视版本可能已修复）|
| `box.go` | PlatformLogWriter 强制启用 cache/API | Android 集成 |
| `protocol/group/selector.go` | 选中出站时回调 | UI 通知 |
| `route/rule_set_local.go` | `geoip:`/`geosite:` 前缀 | 内置规则加载 |

### 步骤 3: 检查 Deprecated 字段

访问官方文档：https://sing-box.sagernet.org/deprecated/

关键迁移点（1.12 → 1.13 经验）：
- ✅ Inbound: `sniff`/`domain_strategy` → route rule actions
- ✅ TUN: `inet4_address`/`inet6_address` → `address`
- ✅ Direct inbound: `override_address` 已移除
- ✅ WireGuard: outbound → endpoint 架构

### 步骤 4: 评估 Android 适用新特性

**筛选标准**：
- ❌ 跳过 Linux 专属（kernel TLS offload, auto_redirect bypass, MPTCP）
- ✅ 优先低风险高价值（TLS Fragment, ECH, 证书管理）
- ⚠️ 谨慎评估原生集成（cronet, 需要 NDK 链接的特性）

---

## 技术架构关键点

### 1. 代码库结构

```
nekobox/
├── app/                          # Android 应用层
│   └── src/main/java/
│       ├── io/nekohasekai/sagernet/
│       │   ├── fmt/             # 协议格式转换（Bean ↔ sing-box config）
│       │   │   ├── ConfigBuilder.kt   # 核心：生成 sing-box JSON 配置
│       │   │   ├── naive/NaiveFmt.kt
│       │   │   ├── v2ray/V2RayFmt.kt
│       │   │   └── ...
│       │   ├── database/         # 配置存储（Kryo 序列化）
│       │   │   └── ProxyEntity.kt
│       │   └── ui/              # 设置界面
│       └── moe/matsuri/nb4a/
│           └── SingBoxOptions.java   # sing-box option 字段的 Java 映射
├── libcore/                      # Go 核心封装
│   ├── box.go                    # libbox 初始化与 Android 桥接
│   ├── box_include.go            # 注册 outbound/endpoint
│   ├── build.sh                  # gomobile 构建脚本
│   ├── go.mod                    # 主模块依赖
│   └── init.sh                   # 安装 gomobile-matsuri
├── sing-box/                     # sing-box 源码（submodule 或本地）
│   ├── option/                   # 配置 schema（与官方对齐）
│   ├── protocol/                 # 协议实现
│   └── boxapi/nekoutils/         # MatsuriDayo 添加的桥接包
├── buildScript/
│   └── lib/core/
│       └── get_source.sh         # 拉取 sing-box 源码
└── .github/workflows/
    ├── preview.yml               # 构建流程
    └── release.yml
```

### 2. 配置生成流程

```
用户 UI 输入
    ↓
Bean (Kotlin data class)
    ↓ [序列化]
Database (Kryo)
    ↓ [反序列化]
ConfigBuilder.kt
    ↓ [buildSingBoxOutbound...]
SingBoxOptions (Java POJO)
    ↓ [Gson.toJson]
sing-box JSON config
    ↓
libcore.NewSingBoxInstance
    ↓
sing-box 核心运行
```

### 3. 关键依赖关系

```
Go 1.24+ (1.13 要求) / 1.25+ (当前)
    ↓
gomobile-matsuri (MatsuriDayo/gomobile:master2)
    ↓ [gobind]
libcore/*.go
    ↓ [sing-box replace]
sing-box 1.13.x-neko
    ↓ [sing 依赖]
sagernet/sing v0.8.x (1.13 要求，从 v0.7.x 升级)
```

**依赖陷阱**：
- `libcore/go.mod` 是主模块，`sing-box/go.mod` 被 replace 忽略
- MVS 算法选最高版本：libcore go.sum 必须包含所有间接依赖
- gomobile-matsuri 必须与 Go 版本匹配，否则 gobind 失败

### 4. 构建流程（GitHub Actions）

```yaml
# .github/workflows/preview.yml
jobs:
  libcore:
    - Setup Go 1.25
    - Get sing-box source (buildScript/lib/core/get_source.sh)
    - Restore cache (key: workflow hash + buildScript + libcore + sing-box)
    - cd libcore && ./build.sh
    - Upload libcore.aar as artifact
  
  apk:
    - Download libcore.aar
    - Copy to app/libs/
    - ./gradlew app:assemblePreviewRelease
    - Upload APK
```

**缓存策略**：
- 缓存键包含：workflow 文件、buildScript、libcore、sing-box 子模块
- 任一变化 → 缓存失效 → 完整重编 Go（~10 分钟）
- 仅改 app → 缓存命中（~3-4 分钟）

---

## 完整升级流程

### Phase 0: 环境准备与基线稳定

#### 0.1 确认工具链

```bash
# 必需工具
go version          # 需要 1.24+（1.13）或 1.25+（当前）
java -version       # JDK 17+
echo $ANDROID_NDK_HOME  # NDK 27+

# gomobile-matsuri
$(go env GOPATH)/bin/gomobile-matsuri version
# 如果没有：cd libcore && ./init.sh
```

#### 0.2 创建专用分支

```bash
# 创建备份
git checkout main
git checkout -b main-backup-before-1.14-upgrade
git push origin main-backup-before-1.14-upgrade

# 创建工作分支
git checkout -b feature/singbox-1.14-upgrade
```

#### 0.3 解耦高风险特性

**原则**：先稳定版本升级，再添加新特性

```bash
# 示例：如果当前有构建失败的原生特性
# 1. 禁用构建标签
vim libcore/build.sh
# 从 -tags 移除 with_naive_outbound（或其他失败的特性）

# 2. 还原 ldflags
# -ldflags='-s -w'  # 标准
# 而非 -extldflags="-Wl,--allow-shlib-undefined ..."
```

#### 0.4 更新 sing-box 源码

```bash
# 方式 1: 更新 submodule（如果用 submodule）
cd sing-box
git fetch origin
git checkout v1.14.0  # 或目标版本
cd ..
git add sing-box
git commit -m "chore: update sing-box to 1.14.0"

# 方式 2: 修改 get_source.sh（如果动态拉取）
vim buildScript/lib/core/get_source.sh
# 修改 COMMIT_SING_BOX 或分支名
```

#### 0.5 应用必需 patch

**必需 patch 清单**（参考 MatsuriDayo/sing-box 对应版本）：

1. **DoNotSelectInterface patch** - `sing-box/common/dialer/default.go`
   ```go
   // 在文件顶部加全局变量
   var DoNotSelectInterface = false
   
   // 在 DialContext 和 ListenPacket 修改判断
   if DoNotSelectInterface || d.networkStrategy == nil {
       // ... 跳过接口选择
   }
   ```
   
   然后在 `libcore/box.go` 取消注释：
   ```go
   import "github.com/sagernet/sing-box/common/dialer"
   // ...
   dialer.DoNotSelectInterface = true
   ```

2. **VLESS multiplex flow patch** - `sing-box/protocol/vless/outbound.go`
   ```go
   // 在 vless.NewClient 调用前
   muxOpts := common.PtrValueOrDefault(options.Multiplex)
   if muxOpts.Enabled {
       options.Flow = ""
   }
   ```

3. **GVisor TUN 修复**（视版本可能已修复）
   - 检查 `sing-tun` 版本是否已包含 `endpoint.Attach(nil)`
   - 如未包含，从 MatsuriDayo 版本复制 `fix_gvisor.go`

4. **其他 MatsuriDayo 辅助包**
   - `boxapi/` - V2Ray API、代理拨号器
   - `nekoutils/` - 选择器回调、SRS 接口
   - 通常可直接复制，API 变动较少

#### 0.6 Go 层构建验证

```bash
# 检查 sing-box 编译
cd sing-box
go build ./...
go vet ./...

# 检查 libcore 编译
cd ../libcore
go build ./...
go mod tidy  # 清理依赖

# 完整构建（需要 NDK）
./build.sh
# 产出：app/libs/libcore.aar
```

#### 0.7 推送并触发 CI

```bash
# 提交所有改动
git add .
git commit -m "feat: upgrade to sing-box 1.14.0 baseline"
git push origin feature/singbox-1.14-upgrade

# 触发构建（GitHub API）
curl -X POST \
  -H "Authorization: token YOUR_GITHUB_TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  "https://api.github.com/repos/YOUR_USER/YOUR_REPO/actions/workflows/preview.yml/dispatches" \
  -d '{"ref":"feature/singbox-1.14-upgrade"}'
```

**预期结果**：
- ✅ Native Build (LibCore) 成功
- ✅ Build OSS APK 成功
- ⚠️ 如果失败，查看 CI 日志定位问题

---

### Phase 1: 修复运行时阻断（Legacy 字段迁移）

#### 1.1 识别被移除的字段

查看 sing-box 源码中的硬拒绝检查：

```bash
cd sing-box
grep -r "removed in sing-box" option/
# 示例输出：
# option/inbound.go:50: legacy inbound fields are deprecated...
# option/tun.go:61: legacy tun address fields are deprecated...
```

#### 1.2 常见迁移（1.12 → 1.13 经验）

**Inbound sniff/domain_strategy → route rule actions**

`ConfigBuilder.kt` 修改：
```kotlin
// 删除 inbound 的这些字段
// inbound.sniff = true
// inbound.domain_strategy = ...

// 改为在 route.rules 开头注入 action
val rules = mutableListOf<Rule>()
if (needSniff) {
    rules.add(Rule().apply {
        action = "sniff"
    })
}
if (domainStrategy.isNotBlank()) {
    rules.add(Rule().apply {
        action = "resolve"
        strategy = domainStrategy
    })
}
```

**TUN 地址字段合并**

`ConfigBuilder.kt` 修改：
```kotlin
// 旧版（1.11-）
tunOptions.inet4_address = listOf("172.19.0.1/30")
tunOptions.inet6_address = listOf("fdfe:dcba:9876::1/126")

// 新版（1.12+）
tunOptions.address = mutableListOf<String>().apply {
    add("172.19.0.1/30")
    if (ipv6Mode != IPv6Mode.DISABLE) {
        add("fdfe:dcba:9876::1/126")
    }
}
```

**Direct inbound override（外部插件场景）**

如果使用外部插件的 mapping 路径：
```kotlin
// 1.12 及之前
directInbound.override_address = mappedAddress
directInbound.override_port = mappedPort

// 1.13+ 需要改用 route rule action
// 或确认该场景是否仍需支持
```

#### 1.3 逐个真机验证

每次迁移一个字段后：
1. 构建新 APK
2. 安装到真机
3. 启用节点，查看日志
4. 确认 "legacy ... removed" 错误消失

---

### Phase 2: 修复 libcore 架构变化

#### 2.1 PlatformInterface 注册（1.13 新增）

**问题**: `netlink socket in Android is banned by Google`

**根因**: sing-box 1.13 的 `NewNetworkManager` 检查 `platformInterface != nil`，
nil 时用 netlink（Android 禁止）。

**修复**:

1. **sing-box 侧** - 导出注册函数：
   ```go
   // sing-box/experimental/libbox/platform.go
   func RegisterPlatformInterface(
       ctx context.Context,
       iif PlatformInterface,
   ) {
       service.MustRegister[adapter.PlatformInterface](
           ctx,
           adapter.PlatformInterface(platformInterfaceWrapper{iif, &boxSingleton}),
       )
   }
   ```

2. **libcore 侧** - 调用注册：
   ```go
   // libcore/box.go
   import "github.com/sagernet/sing-box/experimental/libbox"
   
   // 在 setup() 中
   func (b *Box) setup() error {
       // ...
       libbox.RegisterPlatformInterface(ctx, b) // 替代原来的 service.MustRegister
       // ...
   }
   ```

#### 2.2 WireGuard endpoint 架构（1.13 重构）

**问题**: WireGuard 从 outbound 改为 endpoint

**修复**:

1. **注册 endpoint 而非 outbound**:
   ```go
   // libcore/box_include.go
   // 注释掉（如果有）
   // registry.RegisterOutbound(wireguard.NewOutbound)
   
   // 启用
   registry.RegisterEndpoint(wireguard.NewEndpoint)
   ```

2. **ConfigBuilder 改为生成 endpoint**:
   ```kotlin
   // WireGuardFmt.kt
   fun buildSingBoxEndpoint(bean: WireGuardBean): Endpoint {
       return Endpoint().apply {
           type = "wireguard"
           tag = bean.name
           // ... 其他配置
       }
   }
   
   // ConfigBuilder.kt
   val endpoints = mutableListOf<Endpoint>()
   if (proxy.type == ProxyEntity.TYPE_WIREGUARD) {
       endpoints.add(buildSingBoxEndpoint(proxy.wireguardBean))
   }
   config.endpoints = endpoints
   ```

---

### Phase 3: 添加新特性（按优先级）

#### 通用添加流程

新增 sing-box option 字段的标准 6 步：

1. **SingBoxOptions.java** - 添加字段
   ```java
   // 必须与 sing-box option 的 JSON tag 完全一致
   public class OutboundTLSOptions {
       public Boolean fragment;  // 对应 sing-box 的 "fragment"
       public Integer fragment_fallback_delay;  // 下划线！
   }
   ```

2. **Bean.java** - 添加字段并序列化
   ```java
   public class StandardV2RayBean extends AbstractBean {
       public Boolean enableTLSFragment = false;
       public Integer tlsFragmentFallbackDelay = 50;
       
       @Override
       public void serialize(ByteBuffer buffer) {
           buffer.put(version);
           if (version >= 6) {
               buffer.put((byte) (enableTLSFragment ? 1 : 0));
               buffer.putInt(tlsFragmentFallbackDelay);
           }
       }
       
       @Override
       public void deserialize(ByteBuffer buffer) {
           version = buffer.get();
           if (version >= 6) {
               enableTLSFragment = buffer.get() == 1;
               tlsFragmentFallbackDelay = buffer.getInt();
           }
       }
       
       // 记得升级 version 常量
       public static final int VERSION = 6;  // 从 5 升到 6
   }
   ```

3. **Fmt.kt** - 映射 bean → option
   ```kotlin
   fun buildSingBoxOutboundTLS(bean: StandardV2RayBean): OutboundTLSOptions {
       return OutboundTLSOptions().apply {
           if (bean.enableTLSFragment) {
               fragment = true
               fragment_fallback_delay = bean.tlsFragmentFallbackDelay
           }
       }
   }
   ```

4. **preferences.xml** - UI 开关
   ```xml
   <PreferenceCategory android:title="TLS Settings">
       <SwitchPreferenceCompat
           android:key="enableTLSFragment"
           android:title="Enable TLS Fragment"
           android:defaultValue="false" />
       <EditTextPreference
           android:key="tlsFragmentFallbackDelay"
           android:title="Fragment Fallback Delay (ms)"
           android:defaultValue="50"
           android:inputType="number"
           android:dependency="enableTLSFragment" />
   </PreferenceCategory>
   ```

5. **SettingsActivity.kt** - 绑定 UI
   ```kotlin
   class StandardV2RaySettingsActivity : ProfileSettingsActivity() {
       override fun PreferenceFragmentCompat.createPreferences() {
           val pbm = PreferenceBindingManager()
           pbm.add(PreferenceBinding(Type.Switch, "enableTLSFragment"))
           pbm.add(PreferenceBinding(Type.Text, "tlsFragmentFallbackDelay"))
           // ...
       }
   }
   ```

6. **ConfigBuilder.kt** - 全局配置（如果是全局级）
   ```kotlin
   // 如果是路由级或全局级配置
   route.rules.add(Rule().apply {
       action = "tls_fragment"
       // ...
   })
   ```

---

## 常见陷阱与解决方案

### 1. 构建通过但运行失败

**现象**: GitHub Actions 全绿，APK 安装后启动节点报错

**原因**: 
- Legacy 字段检查在运行时，不在编译时
- sing-box 的配置验证在 `box.New()` 时才执行

**解决方案**:
```bash
# 必须真机测试
1. 安装 APK
2. 启用 VPN 和节点
3. adb logcat | grep -E "(sing-box|libcore|ERROR)"
4. 查找 "deprecated" / "removed" / "legacy" 关键词
```

**已知错误模式**:
- `legacy inbound fields are deprecated in sing-box 1.11.0 and removed in sing-box 1.13.0`
  - → 迁移 inbound sniff/domain_strategy 到 route rule actions
- `legacy tun address fields ... removed in sing-box 1.12.0`
  - → 合并 inet4_address/inet6_address 为 address
- `netlink socket in Android is banned`
  - → 注册 PlatformInterface

### 2. FakeDNS 与新版本不兼容

**现象**: 节点连上但无法浏览网页

**1.13 问题**: FakeDNS 对非 A/AAAA 查询返回错误（旧版返回 NODATA）

**解决方案**:
```kotlin
// ConfigBuilder.kt
val fakeipRule = DefaultDNSRule().apply {
    inbound = listOf("tun-in")
    query_type = listOf("A", "AAAA")  // 关键！限制查询类型
    server = "dns-fake"
}
```

**或者默认关闭 FakeDNS**:
```kotlin
// DataStore.kt
var enableFakeDns by configurationStore.boolean(
    Key.ENABLE_FAKEDNS,
    false  // 改为 false
)
```

### 3. route.final 默认行为变化

**现象**: 未匹配规则的流量走 direct 而非 proxy

**1.13 变化**: 空 `route.final` 不再用第一个 outbound，而是创建 DIRECT 兜底

**解决方案**:
```kotlin
// ConfigBuilder.kt
route.final_ = TAG_PROXY  // 显式指定默认出站
```

### 4. 原生依赖链接失败（cronet, NDK）

**现象**: `unknown relocation type 315` / `R_AARCH64_PREL32`

**原因**: cronet-go 预编译对象与 NDK 版本不兼容

**解决方案 A - 外部插件**（推荐）:
```bash
# 从预编译插件提取 .so 打包进 APK
bash download_naive.sh  # 下载并抽取 libnaive.so
# 放入 app/src/main/jniLibs/arm64-v8a/
# 作为子进程运行，不链接进 libcore
```

**解决方案 B - 继续调试原生**:
```bash
# 尝试不同 NDK 版本
export ANDROID_NDK_HOME=/path/to/ndk/27.x.x
# 或 26.x.x

# 固定 cronet-go 版本
cd libcore
go mod edit -replace=github.com/sagernet/cronet-go@v0.0.0-20260413=...
go mod tidy
```

**教训**: 
- 原生集成风险高，优先考虑外部插件方案
- 不要与版本升级耦合

### 5. Bean 序列化版本升级回归

**现象**: 更新后旧节点配置丢失或崩溃

**原因**: 改动 Bean 字段但未正确升级序列化版本

**正确流程**:
```java
public class SomeBean extends AbstractBean {
    // 1. 升级 version 常量
    public static final int VERSION = 3;  // 从 2 升到 3
    
    // 2. 新增字段
    public String newField = "default";
    
    // 3. 序列化时写入（末尾追加）
    @Override
    public void serialize(ByteBuffer buffer) {
        buffer.put(version);
        // ... 旧字段
        if (version >= 3) {
            buffer.putString(newField);  // 新字段
        }
    }
    
    // 4. 反序列化时读取（版本守卫）
    @Override
    public void deserialize(ByteBuffer buffer) {
        version = buffer.get();
        // ... 旧字段
        if (version >= 3) {
            newField = buffer.getString();
        } else {
            newField = "default";  // 旧版本用默认值
        }
    }
}
```

**验证**:
1. 导出旧版本的配置备份
2. 安装新版
3. 导入配置，确认所有字段正确

### 6. gomobile-matsuri 与 Go 版本不匹配

**现象**: `gobind` 执行失败 / 找不到符号

**原因**: gomobile-matsuri 基于特定 Go 版本

**解决方案**:
```bash
# 重新安装 gomobile-matsuri
cd libcore
./init.sh

# 如果还失败，检查 MatsuriDayo/gomobile 是否有对应 Go 版本的分支
# Go 1.24/1.25 应该用 master2 分支
```

### 7. libcore go.sum 与 sing-box go.mod 冲突

**现象**: 构建时报找不到依赖的特定版本

**原因**: MVS 算法 + replace 导致版本选择问题

**解决方案**:
```bash
# libcore 是主模块，它的 go.sum 是权威
cd libcore
go mod tidy  # 自动解决

# 如果 sing-box 引入了新依赖
cd ../sing-box
go list -m all  # 查看实际版本
# 手动在 libcore/go.sum 补充（或让 go mod tidy 自动补）
```

---

## 验证测试方法

### 构建级验证

```bash
# Go 层
cd sing-box && go build ./... && go vet ./...
cd ../libcore && go build ./... && go vet ./...

# 完整链
cd libcore && ./build.sh
# 成功 → app/libs/libcore.aar 存在

# APK
./gradlew :app:assembleDebug
# 或推 CI
```

### 运行时验证（必需！）

#### 基础冒烟测试

```bash
1. 安装 APK
2. 添加一个测试节点（vmess/trojan/vless）
3. 启用 VPN
4. 启用节点

# 查看日志
adb logcat | grep -E "(sing-box|libcore|ERROR|FATAL)"

# 预期：无 "legacy"/"deprecated"/"removed" 错误
```

#### 功能测试清单

| 测试项 | 验证内容 | 检查点 |
|--------|----------|--------|
| **基础连通** | 能否连接节点 | VPN 启动、节点延迟测试成功 |
| **网页浏览** | 代理流量是否正常 | 打开 google.com / youtube.com |
| **DNS 解析** | DNS 是否工作 | 查看日志 `dns: exchange ... IN A` |
| **域名分流** | sniff 是否生效 | 日志显示 `match ... domain=...` |
| **IP 分流** | resolve 是否生效 | geoip 规则命中 |
| **直连流量** | direct 路由 | 国内站点直连（如果有规则）|
| **默认路由** | route.final | 无匹配规则时走 proxy |
| **URLTest** | 延迟测试 | 显示正常延迟值 |
| **配置保存** | 序列化/反序列化 | 重启 APP 配置不丢失 |

#### 协议特定测试

```bash
# VMess/VLESS/Trojan - 基础协议
测试：能连、能浏览、延迟正常

# WireGuard（1.13 改 endpoint）
测试：endpoint 注册、连接成功、TUN 内 ping 通

# Tailscale
测试：能加入 tailnet、advertise 路由生效

# NaiveProxy（外部插件）
测试：插件子进程启动、SOCKS 端口工作
日志：GuardedProcessPool 显示 naive 进程
```

#### 新特性验证

**TLS Fragment**:
```bash
# 抓包验证
tcpdump -i any -w capture.pcap port 443
# 查看 ClientHello 是否分片
```

**ECH**:
```bash
# 日志确认
adb logcat | grep -i "ech"
```

**FakeDNS**:
```bash
# 开启后测试
浏览多个网站，确认无 "only IP queries" 错误
```

**证书管理**:
```bash
# 添加自签 CA
粘贴自签证书到全局设置
测试用该 CA 签发的节点
```

---

## 附录：工具与命令

### A. 快速诊断脚本

```bash
#!/bin/bash
# check-upgrade-readiness.sh - 升级前环境检查

echo "=== 环境检查 ==="
echo "Go 版本: $(go version)"
echo "Java 版本: $(java -version 2>&1 | head -1)"
echo "NDK 路径: $ANDROID_NDK_HOME"
echo "gomobile: $(which gomobile-matsuri)"

echo -e "\n=== sing-box 版本 ==="
grep 'Version =' sing-box/constant/version.go

echo -e "\n=== libcore 依赖 ==="
cd libcore
go list -m github.com/sagernet/sing
go list -m github.com/sagernet/sing-box

echo -e "\n=== 构建测试 ==="
go build ./... && echo "✅ libcore 编译通过" || echo "❌ libcore 编译失败"

cd ../sing-box
go build ./... && echo "✅ sing-box 编译通过" || echo "❌ sing-box 编译失败"
```

### B. 日志分析命令

```bash
# 实时过滤关键错误
adb logcat | grep -E "(ERROR|FATAL|deprecated|removed|legacy)"

# 查看 sing-box 配置
adb logcat | grep -A 20 "sing-box config:"

# 查看路由匹配
adb logcat | grep "match\["

# 查看 DNS 查询
adb logcat | grep "dns: exchange"

# 查看外部插件进程
adb logcat | grep "GuardedProcessPool"
```

### C. CI 快速触发

```bash
# 函数：触发 GitHub Actions
trigger_build() {
    local branch=${1:-main}
    curl -X POST \
        -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$GITHUB_REPO/actions/workflows/preview.yml/dispatches" \
        -d "{\"ref\":\"$branch\"}"
    echo "✅ 已触发 $branch 分支构建"
}

# 使用
export GITHUB_TOKEN="your_token"
export GITHUB_REPO="user/repo"
trigger_build feature/singbox-1.14-upgrade
```

### D. 常用 git 操作

```bash
# 创建升级分支并备份
git checkout main
git pull origin main
git checkout -b main-backup-before-1.14
git push origin main-backup-before-1.14
git checkout -b feature/singbox-1.14-upgrade

# 更新 submodule（如果用）
git submodule update --remote sing-box
git add sing-box
git commit -m "chore: update sing-box submodule to 1.14.0"

# 推送多个仓库（主仓库 + sing-box fork）
git push origin feature/singbox-1.14-upgrade
cd sing-box
git push my-singbox-fork 1.14.x-neko
```

### E. 版本对比表（历史参考）

| 升级路径 | sing 库 | Go 版本 | 关键变化 | 难度 |
|----------|---------|---------|----------|------|
| 1.11 → 1.12 | v0.7.x | 1.23 | TUN 地址合并 | 🟢 低 |
| 1.12 → 1.13 | v0.8.x | 1.24+ | inbound 迁移、endpoint 架构 | 🟡 中 |
| 1.13 → 1.14 | v0.9.x? | 1.24+ | typed DNS、证书修复 | 🔴 高 |

---

## 总结：下次升级 Checklist

### 准备阶段
- [ ] 阅读官方 Changelog 和 Deprecated 文档
- [ ] 对比 MatsuriDayo 魔改差异（diff option/）
- [ ] 识别必需 patch 清单
- [ ] 评估 Android 适用新特性

### 实施阶段
- [ ] 创建备份分支
- [ ] 创建工作分支
- [ ] 更新 sing-box 源码
- [ ] 应用必需 patch
- [ ] Go 层编译验证
- [ ] 推送触发 CI
- [ ] **构建通过后立即真机测试**

### 修复阶段
- [ ] 逐个修复运行时错误（legacy 字段）
- [ ] 修复 libcore 架构变化
- [ ] 验证所有协议类型
- [ ] 回归测试旧功能

### 增量阶段
- [ ] 按优先级添加新特性
- [ ] 每个特性独立测试
- [ ] 更新用户文档

### 收尾阶段
- [ ] 完整功能测试
- [ ] 性能对比测试
- [ ] 更新 README 和版本号
- [ ] 合并到 main
- [ ] 标记 release tag

---

**关键原则**：
1. ✅ 拆分关注点 - 版本升级与新特性分开
2. ✅ 先稳后快 - 先绿色基线，再添加功能
3. ✅ 构建≠运行 - 必须真机验证
4. ✅ 增量验证 - 每步都测试，不要累积问题
5. ✅ 备份为先 - 随时可回滚

---

**文档维护**：
- 每次升级后补充新的陷阱和解决方案
- 更新版本对比表
- 记录特定版本的特殊问题

**相关文档**：
- [NekoBox sing-box 升级评估](./nekobox-singbox-upgrade-evaluation.md)
- [Native Naive 实施记录](./swirling-fluttering-fiddle.md)
- [架构优化文档](./phase2-architecture-optimization-completed.md)

