# Feature/Native-Naive-POC 分支优化完成报告

## 执行日期：2026-06-13

---

## ✅ 已完成的优化任务

### 一、移除推广和文档菜单项
**Commit**: `318c668` - refactor: remove promotion and document menu items

**修改内容**：
1. `app/src/main/res/menu/main_drawer_menu.xml`
   - 删除 `nav_tuiguang`（推广）菜单项
   - 删除 `nav_faq`（文档）菜单项

2. `app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt`
   - 移除 `R.id.nav_faq` 点击处理（跳转到 matsuridayo.github.io）
   - 移除 `R.id.nav_tuiguang` 点击处理（跳转到 neko-box.pages.dev）
   - 移除 `refreshNavMenu()` 中的 `nav_tuiguang` 可见性控制

**影响**：
- 简化主菜单，移除无关的推广和文档入口
- 用户可以通过关于页面的 GitHub 链接获取帮助

---

### 二、优化关于页面 - 移除捐款项
**Commit**: `95b76a6` - refactor: remove donation item from about page

**修改内容**：
1. `app/src/main/java/io/nekohasekai/sagernet/ui/AboutFragment.kt`
   - 删除捐款 `MaterialAboutActionItem`（指向 matsuridayo.github.io/index_docs/#donate）

**影响**：
- 移除不相关的捐款项（本 fork 不接受捐款）
- 关于页面更简洁

---

### 三、优化关于页面 - 修改 GitHub 和移除 Telegram
**Commit**: `65b73d3` - refactor: update GitHub link to fork repo and remove Telegram item

**修改内容**：
1. `app/src/main/java/io/nekohasekai/sagernet/ui/AboutFragment.kt`
   - GitHub 链接从 `MatsuriDayo/NekoBoxForAndroid` 改为 `rainsmen/nekoboxi`
   - 完全移除 Telegram 菜单项（t.me/MatsuriDayo）

**影响**：
- 用户点击 GitHub 链接会跳转到正确的 fork 仓库
- 移除不相关的上游社区链接

---

### 四、优化关于页面 - 添加 fork 说明到授权协议
**Commit**: `38d1efe` - feat: add fork notice to license display

**修改内容**：
1. `app/src/main/java/io/nekohasekai/sagernet/ui/AboutFragment.kt`
   - 在 LICENSE 文本前添加 fork 说明：
     ```
     ThBox for Android
     Based on NekoBox for Android (https://github.com/MatsuriDayo/NekoBoxForAndroid)
     Fork Repository: https://github.com/rainsmen/nekoboxi
     
     ---
     ```

**影响**：
- 明确标识 ThBox 是 NekoBox 的 fork
- 符合开源协议要求，注明原始项目和 fork 关系
- 用户可以直接点击链接访问两个仓库

---

### 五、ProGuard 配置优化
**Commit**: `8db165d` - feat: enable minify and shrink resources for release build

**修改内容**：
1. `buildSrc/src/main/kotlin/Helpers.kt`
   - 为 release 构建类型添加：
     - `isMinifyEnabled = true` - 启用代码压缩
     - `isShrinkResources = true` - 启用资源压缩

**影响**：
- 减少 APK 体积（预计 2-5 MB）
- 移除未使用的代码和资源
- 提升安全性（代码混淆）

**注意事项**：
- 当前 `proguard-rules.pro` 中有 `-dontobfuscate`，保留符号名便于调试
- 已有 `-keep class io.nekohasekai.sagernet.** { *;}` 规则，保留所有应用类
- 配置较为保守，优先保证稳定性

---

## 📊 优化效果预估

### APK 体积优化
| 优化项 | 预期收益 | 状态 |
|--------|---------|------|
| 启用 minify 和 shrinkResources | 2-5 MB | ✅ 已完成 |
| 移除外部 naive 插件 | 4-6 MB | ✅ 已完成（随原生 Naive） |
| **总计** | **6-11 MB** | ✅ |

### 用户体验优化
- ✅ 菜单更简洁，移除无关项
- ✅ 链接指向正确，避免混淆
- ✅ 明确 fork 身份，符合开源规范

---

## 🔍 待验证事项

### 构建验证
- [ ] 触发 GitHub Actions Preview Build
- [ ] 确认 APK 构建成功
- [ ] 验证 APK 大小是否减小
- [ ] 检查 ProGuard 输出日志

### 功能验证
- [ ] 安装 APK 并运行
- [ ] 验证主菜单：确认推广和文档项已移除
- [ ] 验证关于页面：
  - [ ] 捐款项已移除
  - [ ] GitHub 链接指向 rainsmen/nekoboxi
  - [ ] Telegram 项已移除
  - [ ] 授权协议显示 fork 说明
- [ ] 验证应用功能正常（ProGuard 未破坏反射）

---

## 📝 后续任务（可选，roadmap Phase 2）

### APK 体积进一步优化
1. **评估 libcore 构建标签**（roadmap Phase 3.4）
   - 评估 `with_clash_api` 使用率
   - 如果不需要 Dashboard，可以移除（减少 1-4 MB）

2. **Dashboard 在线加载**（roadmap Phase 3.5）
   - 将 `yacd.zip` (741 KB) 改为首次使用时下载
   - 添加离线缓存机制
   - 工作量：3-4 小时

### ProGuard 进一步优化（需谨慎测试）
1. **移除 `-dontobfuscate`**
   - 可以进一步减小体积
   - 但可能破坏反射调用
   - 需要充分测试 Room、Gson、插件系统

2. **精简 `-keep` 规则**
   - 当前保留了所有应用类
   - 可以改为只保留必要的类（如 Room entities、Gson models）
   - 需要逐步测试，风险较高

---

## 🚀 下一步行动

### 立即执行
1. ✅ 将改动推送到远程分支
2. ⏳ 触发 GitHub Actions 构建
3. ⏳ 下载并测试 APK

### 验证通过后
4. ⏸️ 在 roadmap 中标记 Phase 1 和 Phase 3.3 已完成
5. ⏸️ 更新相关文档
6. ⏸️ 继续执行 roadmap 中的其他任务

### 合并到 main（需等待）
- ⚠️ **不要立即合并到 main**
- 等待 feature 分支所有优化和测试完成
- 按照 roadmap 的 Week 5 计划再合并

---

## 📦 提交记录

```bash
ecf1cf5 docs: add optimization roadmap and update evaluation report
7c33e87 docs: add main branch optimization plan
318c668 refactor: remove promotion and document menu items
95b76a6 refactor: remove donation item from about page
65b73d3 refactor: update GitHub link to fork repo and remove Telegram item
38d1efe feat: add fork notice to license display
8db165d feat: enable minify and shrink resources for release build
```

---

## 📋 任务检查清单

### 代码修改
- [x] 移除推广菜单项
- [x] 移除文档菜单项
- [x] 移除捐款项
- [x] 修改 GitHub 链接
- [x] 移除 Telegram 链接
- [x] 添加 fork 说明
- [x] 启用 minify
- [x] 启用 shrinkResources

### 文档更新
- [x] 创建优化计划文档
- [x] 创建完成报告
- [x] 更新 roadmap

### 验证测试
- [ ] GitHub Actions 构建
- [ ] APK 功能测试
- [ ] APK 体积验证

---

## 💡 重要提醒

1. **当前分支**: `feature/native-naive-poc`
2. **禁止操作**: 不要合并到 main 分支
3. **下一步**: 等待构建和测试验证
4. **合并时机**: 按照 roadmap Week 5 计划

---

**文档维护者**：Claude Code  
**最后更新**：2026-06-13  
**完成时间**：约 45 分钟  
**文档版本**：1.0
