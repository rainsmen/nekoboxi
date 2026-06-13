# Roadmap Phase 1: 代码清理任务完成报告

## 执行日期：2026-06-13

---

## ✅ Phase 1 任务完成总结

### 任务 1.1: 移除插件残留代码 ✅

**Commit**: `98924bf` - chore: remove unused naive plugin files

**删除的文件**：
1. `download_naive.sh` (448 bytes) - 下载 naive 插件的脚本
2. `matsuri_naive.so` (3.8 MB) - 测试用的 naive 插件二进制
3. `test_naive.so` (9.7 MB) - 另一个测试用的 naive 插件二进制

**总计删除**: ~13.5 MB 的无用文件

**Commit**: `34fdd7b` - refactor: deprecate external naive plugin code path

**标注废弃的代码**：
1. `app/src/main/java/io/nekohasekai/sagernet/fmt/naive/NaiveFmt.kt`
   - 函数 `buildNaiveConfig(port: Int)` 
   - 添加 `@Deprecated` 注解
   - 说明：外部插件已被原生 outbound 替代

2. `app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt`
   - NaiveBean 的插件配置分支
   - 添加注释说明该分支不再被执行
   - 添加 `@Suppress("DEPRECATION")` 抑制警告

**保留理由**：
- 作为快速回退路径
- 便于理解历史实现
- 避免潜在的未知引用导致编译错误

---

### 任务 1.2: 优化 NDK fallback 逻辑 ✅

**Commit**: `3863a98` - feat: add NDK 28 to 27 fallback for better compatibility

**修改文件**: `buildScript/init/env_ndk.sh`

**优化内容**：
```bash
# 原逻辑：只检查 NDK 28，找不到就失败退出
_NDK=$(ls -d "$ANDROID_HOME/ndk"/28.* 2>/dev/null | sort -V | tail -n 1)
if [ -z "$_NDK" ]; then
  echo "Error: NDK 28 not found..."
  exit 1
fi

# 新逻辑：NDK 28 → 27 优雅降级
_NDK=$(ls -d "$ANDROID_HOME/ndk"/28.* 2>/dev/null | sort -V | tail -n 1)
if [ -z "$_NDK" ]; then
  echo "Warning: NDK 28 not found, falling back to NDK 27..."
  _NDK=$(ls -d "$ANDROID_HOME/ndk"/27.* 2>/dev/null | sort -V | tail -n 1)
fi
if [ -z "$_NDK" ]; then
  echo "Error: NDK 28 or 27 not found..."
  exit 1
fi
```

**收益**：
- ✅ 提高本地开发环境兼容性
- ✅ 避免因 NDK 28 缺失导致构建失败
- ✅ 提供清晰的警告信息
- ✅ 保持 NDK 28 优先策略

---

## 📊 Phase 1 优化效果

| 优化项 | 效果 |
|--------|------|
| 删除无用文件 | 减少 ~13.5 MB 本地文件 |
| 标注废弃代码 | 提高代码可维护性 |
| NDK fallback | 提高构建稳定性 |
| **总计** | 代码更清晰，构建更稳定 |

---

## 🎯 与 Roadmap 的对应关系

根据 `docs/native-naive-optimization-roadmap.md`：

### ✅ 任务 1.1: 移除插件残留代码
- **预计工作量**: 1-2 小时
- **实际工作量**: 约 30 分钟
- **状态**: ✅ 完成
- **交付物**:
  - [x] 删除 3 个文件的 commit
  - [x] 添加 `@Deprecated` 注解的 commit
  - [x] 更新相关代码注释

### ✅ 任务 1.2: 优化 NDK fallback 逻辑
- **预计工作量**: 30 分钟
- **实际工作量**: 约 15 分钟
- **状态**: ✅ 完成
- **交付物**:
  - [x] 修改 `env_ndk.sh` 的 commit
  - [x] 本地测试验证（待 CI 验证）

---

## 📝 提交记录

```bash
3863a98 feat: add NDK 28 to 27 fallback for better compatibility
34fdd7b refactor: deprecate external naive plugin code path
98924bf chore: remove unused naive plugin files
```

**累计提交**（包括之前的优化）：
- 文档：3 个
- UI 优化：5 个
- 代码清理：3 个
- **总计**: 11 个提交

---

## 🔍 验证清单

### 本地验证
- [x] IDE 中 `buildNaiveConfig()` 显示废弃警告
- [x] 无用文件已删除
- [x] NDK fallback 逻辑正确

### CI 验证（待执行）
- [ ] 构建在 NDK 28 环境下成功
- [ ] 构建在 NDK 27 环境下成功（fallback 测试）
- [ ] APK 功能正常

---

## 🚀 下一步：Phase 2 架构优化

根据 roadmap，Phase 2 包含以下任务：

### 任务 2.1: 将 Naive 配置改为强类型
- **预计工作量**: 3-4 小时
- **主要内容**:
  1. 定义 `Outbound_NaiveOptions` 数据类
  2. 定义 `DomainResolverOptions` 数据类
  3. 重写 `buildSingBoxOutboundNaiveBean()`
  4. 单元测试

### 任务 2.2: 抽取统一的 domain_resolver 构造逻辑
- **预计工作量**: 1-2 小时
- **主要内容**:
  1. 提取为独立工具函数
  2. 替换现有调用点
  3. 单元测试

**预计总工作量**: 4-6 小时

---

## 📋 Phase 1 完成状态

### 代码质量
- ✅ 无废弃代码被活跃调用
- ✅ 所有废弃代码已标注
- ✅ 无编译警告（除预期的 @Deprecated）
- ✅ 代码注释清晰

### 构建稳定性
- ✅ NDK fallback 机制完善
- ⏳ 待 CI 验证（需要触发新的构建）

### 文档完整性
- ✅ Roadmap 已更新
- ✅ 完成报告已生成
- ✅ 提交信息清晰

---

## ⚠️ 注意事项

1. **分支状态**: 仍在 `feature/native-naive-poc`，未合并到 main
2. **CI 验证**: 需要触发新构建以验证 NDK fallback 和废弃代码路径
3. **回退路径**: 废弃代码已保留，如需回退可快速恢复
4. **合并时机**: 按 roadmap Week 5 计划，完成 Phase 1-2 后再合并

---

## 💡 建议

### 立即执行
1. ✅ 推送 Phase 1 改动到远程
2. ⏳ 触发 GitHub Actions 验证
3. ⏳ 下载并测试新 APK

### 短期计划（Week 1-2）
4. ⏸️ 开始 Phase 2: 架构优化
   - 任务 2.1: 强类型配置
   - 任务 2.2: domain_resolver 重构

### 中期计划（Week 3-4）
5. ⏸️ 稳定性观察
6. ⏸️ 收集用户反馈

### 长期计划（Week 5）
7. ⏸️ 合并到 main 分支

---

**文档维护者**：Claude Code  
**完成时间**：2026-06-13  
**Phase 1 工作量**：约 45 分钟  
**文档版本**：1.0
