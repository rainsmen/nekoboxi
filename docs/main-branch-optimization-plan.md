# Main 分支优化方案

## 日期：2026-06-13

---

## 一、Release 版本问题优化

### 当前问题分析

**问题描述**：Release workflow 中的版本命名和更新检查逻辑需要优化。

**当前实现**：
1. **AboutFragment.kt** (line 86-88, 222-226)：
   - 版本点击跳转到上游仓库：`https://github.com/MatsuriDayo/NekoBoxForAndroid/releases`
   - 更新检查也指向上游 API：`https://api.github.com/repos/MatsuriDayo/NekoBoxForAndroid/releases`

2. **输出文件命名** (Helpers.kt line 200-209)：
   - Preview 版本：`ThBox-{PRE_VERSION_NAME}-{variant}-{abi}.apk`
   - Release 版本：`ThBox-{VERSION_NAME}-{variant}-{abi}.apk`
   - 当前命名已经正确使用 ThBox 前缀

### 方案 A：完全切换到本 fork 仓库（推荐）

**优点**：
- 用户体验一致，不会误导到上游仓库
- 更新检查指向正确的发布版本
- 符合 fork 独立维护的定位

**缺点**：
- 需要在本仓库持续发布 release 和 preview 版本

**实施步骤**：

1. **修改 AboutFragment.kt**：
```kotlin
// Line 86: 版本点击跳转
.setOnClickAction {
    requireContext().launchCustomTab(
        "https://github.com/rainsmen/nekoboxi/releases"  // 改为本仓库
    )
}

// Line 222-226: 更新检查 API
if (checkPreview) {
    setURL("https://api.github.com/repos/rainsmen/nekoboxi/releases/tags/preview")
} else {
    setURL("https://api.github.com/repos/rainsmen/nekoboxi/releases/latest")
}
```

2. **GitHub Release 发布要求**：
   - 需要创建 `preview` tag 用于 preview 版本检查
   - Release 版本需要使用语义化版本号（如 v1.0.0）
   - Release notes 使用中英双语

3. **更新 README.md**：
   - 确保下载链接指向本仓库

**预计工作量**：30 分钟

---

### 方案 B：保留上游检查 + 本地标识

**优点**：
- 仍可提醒用户上游有新版本
- 本地可明确标识 fork 版本

**缺点**：
- 用户可能混淆上游和 fork 的版本
- 体验不够统一

**实施步骤**：

1. **修改关于页面显示**：
```kotlin
// 添加 fork 标识
.addItem(
    MaterialAboutActionItem.Builder()
        .icon(R.drawable.ic_baseline_info_24)
        .text(R.string.fork_version)
        .subText("ThBox ${SagerNet.appVersionNameForDisplay}")
        .build())
.addItem(
    MaterialAboutActionItem.Builder()
        .icon(R.drawable.ic_baseline_update_24)
        .text(R.string.upstream_version)
        .subText("NekoBox")
        .setOnClickAction {
            requireContext().launchCustomTab(
                "https://github.com/MatsuriDayo/NekoBoxForAndroid/releases"
            )
        }
        .build())
```

2. **添加字符串资源**：
```xml
<string name="fork_version">Fork Version</string>
<string name="upstream_version">Upstream Version</string>
```

**预计工作量**：1 小时

---

### 📌 推荐方案：方案 A

**理由**：
1. ThBox 已经是独立的 fork，应该有独立的版本管理
2. 避免用户混淆，提升品牌一致性
3. 实现简单，维护成本低

**前置要求**：
- 需要在本仓库发布至少一个 release 版本
- 需要创建 `preview` tag（可以是 latest commit）

---

## 二、移除推广和文档页面

### 当前状态

**菜单项定义** (main_drawer_menu.xml line 47-53)：
```xml
<item
    android:id="@+id/nav_tuiguang"
    android:icon="@drawable/ic_social_share"
    android:title="@string/ads" />
<item
    android:id="@+id/nav_faq"
    android:icon="@drawable/ic_device_data_usage"
    android:title="@string/document" />
```

**点击处理** (MainActivity.kt line 333-342)：
```kotlin
R.id.nav_faq -> {
    launchCustomTab("https://matsuridayo.github.io/")
    return false
}
R.id.nav_tuiguang -> {
    launchCustomTab("https://neko-box.pages.dev/喵")
    return false
}
```

**可见性控制** (MainActivity.kt line 122)：
```kotlin
navigation.menu.findItem(R.id.nav_tuiguang)?.isVisible = !isPlay
```

### 实施方案

#### 方案 1：完全移除（推荐）

**实施步骤**：

1. **删除菜单项** (main_drawer_menu.xml)：
```xml
<!-- 删除整个 item -->
<!-- <item android:id="@+id/nav_tuiguang" ... /> -->
<!-- <item android:id="@+id/nav_faq" ... /> -->
```

2. **删除点击处理** (MainActivity.kt)：
```kotlin
// 删除以下代码段：
// R.id.nav_faq -> { ... }
// R.id.nav_tuiguang -> { ... }
```

3. **删除可见性控制** (MainActivity.kt line 122)：
```kotlin
// 删除这一行：
// navigation.menu.findItem(R.id.nav_tuiguang)?.isVisible = !isPlay
```

4. **保留字符串资源**（可选，避免编译错误）：
   - 可以保留 `ads` 和 `document` 字符串定义
   - 或者删除后搜索全局确保无其他引用

**预计工作量**：15 分钟

---

#### 方案 2：替换为 GitHub Issues 链接

**实施步骤**：

1. **保留文档菜单项，修改链接**：
```kotlin
R.id.nav_faq -> {
    launchCustomTab("https://github.com/rainsmen/nekoboxi")
    return false
}
```

2. **删除推广菜单项**（同方案 1）

**预计工作量**：20 分钟

---

### 📌 推荐方案：方案 1（完全移除）

**理由**：
1. 简洁明了，减少无用菜单项
2. 用户可以通过 GitHub 获取帮助
3. 减少维护文档的负担

---

## 三、关于页面优化

### 当前状态分析

**AboutFragment.kt** 包含以下卡片和项目：

#### Card 1: 版本信息和捐款
- ✅ 应用版本 + 点击跳转 release 页面
- ✅ 检查更新（Release）
- ✅ 检查更新（Preview）
- ✅ sing-box 版本
- ❌ **捐款** (line 113-121)：跳转到 `https://matsuridayo.github.io/index_docs/#donate`
- ✅ 已安装插件列表
- ✅ 电池优化提示

#### Card 2: 项目信息
- ⚠️ **GitHub 链接** (line 184-188)：指向上游仓库 `MatsuriDayo/NekoBoxForAndroid`
- ⚠️ **Telegram 链接** (line 193-199)：指向上游频道 `t.me/MatsuriDayo`

#### 底部卡片
- ✅ **授权协议** (layout_about.xml line 76-92)：显示 LICENSE 文件内容

---

### 优化方案

#### 方案 A：精简版（推荐）

**保留内容**：
- ✅ 应用版本（改为指向本仓库）
- ✅ 检查更新（改为本仓库 API）
- ✅ sing-box 版本
- ✅ 已安装插件列表
- ✅ 电池优化提示
- ✅ GitHub 链接（改为本仓库）
- ✅ 授权协议（保留）

**移除内容**：
- ❌ 捐款项
- ❌ Telegram 链接（或替换为本 fork 专属频道）

**修改内容**：
- ⚠️ GitHub 链接改为 `https://github.com/rainsmen/nekoboxi`
- ⚠️ 授权协议文本添加 fork 说明

**实施步骤**：

1. **移除捐款项** (AboutFragment.kt line 112-121)：
```kotlin
// 删除整个 addItem 块
/*
.addItem(
    MaterialAboutActionItem.Builder()
        .icon(R.drawable.ic_baseline_card_giftcard_24)
        .text(R.string.donate)
        .subText(R.string.donate_info)
        .setOnClickAction {
            requireContext().launchCustomTab(
                "https://matsuridayo.github.io/index_docs/#donate"
            )
        }
        .build())
*/
```

2. **修改 GitHub 链接** (AboutFragment.kt line 184-188)：
```kotlin
.addItem(
    MaterialAboutActionItem.Builder()
        .icon(R.drawable.ic_baseline_sanitizer_24)
        .text(R.string.github)
        .setOnClickAction {
            requireContext().launchCustomTab(
                "https://github.com/rainsmen/nekoboxi"  // 改为本仓库
            )
        }
        .build())
```

3. **移除或修改 Telegram 链接** (AboutFragment.kt line 192-200)：

选项 3.1：完全移除
```kotlin
// 删除整个 addItem 块
```

选项 3.2：替换为本 fork 的社区（如果有）
```kotlin
.addItem(
    MaterialAboutActionItem.Builder()
        .icon(R.drawable.ic_qu_shadowsocks_foreground)
        .text(R.string.community)  // 新增字符串
        .setOnClickAction {
            requireContext().launchCustomTab(
                "https://github.com/rainsmen/nekoboxi/discussions"  // 或其他社区链接
            )
        }
        .build())
```

4. **优化授权协议显示**（可选）：

在 LICENSE 文件顶部添加 fork 说明（或在代码中动态添加）：

方式 A：修改 LICENSE 文件（不推荐，会与上游 GPL 冲突）

方式 B：在代码中添加前缀（推荐）：
```kotlin
runOnDefaultDispatcher {
    val license = view.context.assets.open("LICENSE").bufferedReader().readText()
    val forkNotice = """
        ThBox for Android
        Based on NekoBox for Android (https://github.com/MatsuriDayo/NekoBoxForAndroid)
        Fork Repository: https://github.com/rainsmen/nekoboxi
        
        ---
        
    """.trimIndent()
    onMainDispatcher {
        binding.license.text = forkNotice + license
        Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
    }
}
```

**预计工作量**：1-1.5 小时

---

#### 方案 B：最小修改

**仅修改链接指向**，保留所有项目：
- GitHub → 本仓库
- Telegram → 保留或移除
- 捐款 → 保留但注明是给上游

**实施步骤**：

1. 修改链接指向（同方案 A 步骤 2）
2. 在捐款项添加说明：
```kotlin
.text(R.string.donate_upstream)  // "Donate to Upstream"
.subText(R.string.donate_upstream_info)  // "Support NekoBox development"
```

**预计工作量**：30 分钟

---

### 📌 推荐方案：方案 A（精简版）

**理由**：
1. 移除不相关的捐款项（本 fork 不接受捐款）
2. 链接指向正确的仓库，避免混淆
3. 保留必要的法律信息（GPL 许可证）
4. 用户体验更清晰

**需要确认**：
- ❓ 是否有 ThBox 专属的 Telegram/Discord 社区？
  - 如有：替换链接
  - 如无：删除 Telegram 项，或改为 GitHub Discussions

---

## 四、减少安装包体积方案评估

### 当前 APK 体积分析

**预估构成**（arm64-v8a 单架构）：

1. **libcore.aar**（最大部分）：
   - 包含 sing-box Go 编译产物
   - 包含 cronet-go（如果启用 native Naive）
   - 包含 gomobile 绑定代码
   - 预估：15-25 MB

2. **jniLibs** (app/src/main/jniLibs/arm64-v8a)：
   - 当前大小：6.2 MB
   - 包含外部插件 so（如 libnaive.so）

3. **assets**：
   - yacd.zip：741 KB（Web Dashboard）
   - proxy_packagename.txt：13 KB
   - LICENSE：5 KB
   - 总计：~760 KB

4. **APK 基础部分**：
   - Kotlin 运行时
   - Android 依赖库
   - 资源文件
   - Dex 文件
   - 预估：8-12 MB

**预估总大小**：30-45 MB（单架构）

---

### 优化方案

#### 方案 1：R8/ProGuard 优化（低挂果实）

**当前状态**：
- 已启用 ProGuard（release 构建）
- 规则文件：`app/proguard-rules.pro`

**优化措施**：

1. **检查 ProGuard 规则是否过于宽松**：
```bash
# 审计当前规则
cat app/proguard-rules.pro
```

2. **启用更激进的代码优化**：
```gradle
buildTypes {
    release {
        minifyEnabled true
        shrinkResources true  // 确保启用资源压缩
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            file("proguard-rules.pro")
        )
    }
}
```

**预期收益**：减少 2-5 MB  
**风险**：可能破坏反射调用  
**工作量**：1-2 小时测试验证

---

#### 方案 2：移除非必要 assets

**可优化项**：

1. **yacd.zip (741 KB)**：
   - 作用：Web Dashboard UI
   - 评估：如果用户很少使用 Dashboard，可以考虑：
     - 选项 A：完全移除（激进）
     - 选项 B：改为在线加载（首次使用时下载）
     - 选项 C：压缩优化（已经是 zip，收益有限）

2. **proxy_packagename.txt (13 KB)**：
   - 作用：分应用代理的包名列表
   - 评估：必须保留

**推荐**：
- 如果 Dashboard 使用率低，可以改为在线加载
- 首次打开 Dashboard 时从 GitHub 或 CDN 下载

**预期收益**：0-800 KB（取决于是否移除 yacd）  
**风险**：需要网络才能使用 Dashboard  
**工作量**：3-4 小时（实现在线加载机制）

---

#### 方案 3：libcore 构建优化

**优化措施**：

1. **检查 Go build tags**（当前 libcore/build.sh）：
```bash
# 当前 tags
with_conntrack
with_gvisor
with_quic
with_wireguard
with_utls
with_clash_api
with_tailscale
with_naive_outbound  # (feature 分支)
```

**评估每个 tag 的必要性**：
- `with_conntrack`：连接跟踪，必须保留
- `with_gvisor`：网络栈，必须保留
- `with_quic`：QUIC 支持，必须保留（Hysteria/TUIC 依赖）
- `with_wireguard`：WireGuard，必须保留
- `with_utls`：TLS 指纹伪装，常用功能，建议保留
- ⚠️ `with_clash_api`：Clash Dashboard API，如果不用 Dashboard 可以移除
- ⚠️ `with_tailscale`：Tailscale endpoint，取决于用户需求
- ⚠️ `with_naive_outbound`：NaiveProxy，feature 分支特性

**激进方案**：移除 `with_clash_api`
```bash
# libcore/build.sh
-tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_tailscale'
```

2. **Go 编译优化**：
```bash
# 当前已有
-ldflags='-s -w'  # 去除符号表和调试信息

# 可以添加
-ldflags='-s -w -buildid='  # 去除 build ID
-trimpath  # 去除文件路径信息（已有）
```

**预期收益**：
- 移除 `with_clash_api`：减少 1-3 MB
- 编译优化：减少 0.5-1 MB

**风险**：
- 移除 Clash API 后无法使用 Web Dashboard
- 需要与方案 2 配合

**工作量**：1 小时

---

#### 方案 4：移除外部插件（配合 feature 分支）

**当前状态**：
- main 分支：打包 `libnaive.so` (部分 6.2 MB jniLibs)
- feature 分支：已移除外部插件，使用原生 Naive

**优化措施**：
- 合并 feature/native-naive-poc 到 main
- 移除所有外部插件 so

**预期收益**：减少 4-6 MB  
**风险**：已在 feature 分支验证通过  
**工作量**：已完成（随 feature 分支合并）

---

#### 方案 5：多变体构建（推荐给高级用户）

**思路**：提供多个 APK 变体，让用户按需下载

**变体定义**：
1. **Full**（默认）：包含所有功能
2. **Lite**：移除 Dashboard + Clash API + 部分协议
3. **Minimal**：仅保留核心协议（SS/VMess/Trojan/VLESS）

**实现方式**：
```kotlin
// build.gradle.kts
productFlavors {
    create("full") {
        // 默认，所有功能
    }
    create("lite") {
        buildConfigField("Boolean", "ENABLE_DASHBOARD", "false")
        // 构建时使用不同的 libcore（去除 clash_api）
    }
}
```

**预期收益**：
- Full：无变化
- Lite：减少 5-10 MB
- Minimal：减少 15-20 MB

**风险**：增加构建和维护复杂度  
**工作量**：8-12 小时

---

### 📊 方案对比总结

| 方案 | 收益 | 风险 | 工作量 | 优先级 |
|------|------|------|--------|--------|
| 方案 1：ProGuard 优化 | 2-5 MB | 低 | 1-2h | 🔴 高 |
| 方案 2：移除 yacd | 0-800 KB | 中 | 3-4h | 🟡 中 |
| 方案 3：libcore 优化 | 1-4 MB | 中 | 1h | 🟡 中 |
| 方案 4：移除外部插件 | 4-6 MB | 低（已验证）| 0h（随 feature 合并）| 🔴 高 |
| 方案 5：多变体构建 | 5-20 MB | 高 | 8-12h | 🟢 低 |

---

### 📌 推荐执行计划

**Phase 1（立即执行）**：
1. ✅ 方案 4：合并 feature/native-naive-poc（减少 4-6 MB）
2. ✅ 方案 1：优化 ProGuard 配置（减少 2-5 MB）
3. **预期总收益**：6-11 MB

**Phase 2（可选，roadmap 中期）**：
1. 方案 3：评估并移除 `with_clash_api`（如果不需要 Dashboard）
2. 方案 2：Dashboard 改为在线加载
3. **预期总收益**：1-5 MB

**Phase 3（长期，roadmap 后期）**：
1. 方案 5：提供 Lite 变体（给高级用户）
2. **预期总收益**：5-10 MB（相对 Full 版）

---

### 🗺️ 整合到 Roadmap

**建议位置**：

在 `native-naive-optimization-roadmap.md` 中添加：

```markdown
## Phase 3.5: APK 体积优化（中优先级）

### 任务 3.3: ProGuard 规则优化
- 前置条件：Phase 1 和 Phase 2 完成
- 审计并优化 ProGuard 配置
- 启用资源压缩
- 预期收益：减少 2-5 MB
- 工作量：1-2 小时

### 任务 3.4: libcore 构建标签优化
- 评估 `with_clash_api` 和 `with_tailscale` 的使用率
- 根据用户反馈决定是否移除
- 预期收益：减少 1-4 MB
- 工作量：1 小时

### 任务 3.5: Dashboard 在线加载（可选）
- 将 yacd.zip 改为首次使用时下载
- 添加离线缓存机制
- 预期收益：减少 800 KB
- 工作量：3-4 小时
```

---

## 五、总结和优先级

### 立即执行（Week 1）

1. ✅ **移除推广和文档页面**（15 分钟）
2. ✅ **修改关于页面**（1-1.5 小时）
   - 移除捐款项
   - 修改 GitHub 链接为本仓库
   - 移除或替换 Telegram 链接
3. ✅ **修改版本检查逻辑**（30 分钟）
   - 改为指向 `rainsmen/nekoboxi` 仓库
   - 需要先在本仓库创建 release

**总工作量**：2-2.5 小时

---

### 短期执行（Week 2-3）

4. ✅ **ProGuard 优化**（roadmap Phase 3.3）
5. ⏸️ **评估 libcore 构建标签**（roadmap Phase 3.4）

---

### 中长期（按需）

6. ⏸️ **Dashboard 在线加载**（roadmap Phase 3.5）
7. ⏸️ **多变体构建**（roadmap Phase 5）

---

## 六、需要你确认的问题

### 关于 Release 版本优化

❓ **问题 1**：是否已经在 `rainsmen/nekoboxi` 仓库发布过 release？
- [ ] 是，已有 release 和 preview tag
- [ ] 否，需要先发布

如果否，需要：
1. 创建一个 release（如 v1.0.0）
2. 创建 `preview` tag 指向最新 commit

---

### 关于页面优化

❓ **问题 2**：Telegram/社区链接如何处理？
- [ ] A. 完全移除（如果没有 ThBox 专属社区）
- [ ] B. 改为 GitHub Discussions
- [ ] C. 改为其他社区链接（请提供）

---

❓ **问题 3**：授权协议部分是否需要添加 fork 说明？
- [ ] A. 需要，在代码中动态添加前缀
- [ ] B. 不需要，保持原样即可
- [ ] C. 修改 LICENSE 文件本身（不推荐）

---

### APK 体积优化

❓ **问题 4**：是否需要立即执行 APK 体积优化？
- [ ] A. 是，作为 Phase 1 任务（与代码清理并行）
- [ ] B. 否，放到 Phase 3（feature 分支稳定后）
- [ ] C. 暂不考虑，当前体积可接受

---

❓ **问题 5**：是否使用 Web Dashboard 功能？
- [ ] A. 经常使用，必须保留
- [ ] B. 偶尔使用，可以改为在线加载
- [ ] C. 从不使用，可以完全移除

---

### 执行顺序确认

❓ **问题 6**：推荐执行顺序是否可以接受？
- [ ] A. 接受，按照"立即执行 → 短期 → 长期"顺序
- [ ] B. 需要调整优先级（请说明）

---

## 七、下一步行动

**等待你确认上述 6 个问题后，我将：**

1. 根据你的选择生成具体的代码修改
2. 创建新的分支（如 `feature/main-optimization`）
3. 按优先级逐个实现优化任务
4. 每个任务完成后提交 commit，方便 review 和回滚

---

**文档维护者**：Claude Code  
**最后更新**：2026-06-13  
**文档版本**：1.0
