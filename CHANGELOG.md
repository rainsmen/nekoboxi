# 更新日志 (Changelog)

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/) 规范，记录所有重要演进。

---

## [1.5.7] - 2026-09-03

### 优化 (Changed)
- **CI/CD 构建流水线提速**：
  - 移除 `release.yml` 中冗余的 `Build Play Bundle`（.aab 任务），发布流水线耗时缩短约 40%（节省 ~3.5 分钟）；
  - 淘汰 2020 年的第三方发布工具 `ghr`，全面拥抱 GitHub Actions 原生自带的官方 `gh` CLI；
  - 增强 LibCore 编译缓存键，将 `sing-box` 子模组 commit 纳入哈希计算，杜绝内核缓存脏读。
- **JVM 编译规范现代化**：
  - 将 `compileOptions` 源码与目标兼容级别平滑升级至 Java 17；
  - 修复 Gradle 8.10+ 中废弃的 `rootProject.buildDir` API 调用。
- **文档体系完善**：
  - 新增《系统与代码架构优化评估报告》（`docs/code-architecture-and-build-optimization-assessment.md`）；
  - 新增《常见问题与排查指南 (FAQ)》（`docs/troubleshooting-faq.md`）；
  - 补充核心数据流与架构拓扑图（Mermaid）。

---

## [1.5.6] - 2026-09-03

### 修复 (Fixed)
- **修复 DNS 解码失败**：解决启动节点时抛出 `decode config: dns.servers[0]: unknown transport type: rcode` 的致命错误；
- **修复覆盖安装证书冲突**：引入版本库固定签名证书（`keystore/release.keystore`）与自动签名回退逻辑，彻底终结云端随机临时秘钥导致的更新覆盖失败。

### 新增与优化 (Added & Improved)
- **资产导入扩展**：放宽 AssetsActivity 导入格式限制，支持导入 `.srs` 与 `.json` 规则集文件；
- **常态化 DNS 缓存持久化**：在日常运行时启用 `cache_file`（`store_fakeip: true`, `store_rdrc: true`），降低冷启动延迟；
- **多架构 Naive 插件打包**：扩展 `download_naive.sh` 支持 `arm64-v8a`、`armeabi-v7a`、`x86_64` 并实施 SHA256 完整性校验；
- **安全与依赖升级**：升级 `snakeyaml:2.2`、`okhttp:4.12.0`、`com.google.android.material:material:1.12.0`；
- **仓库垃圾清理**：彻底清理 19 份历史草稿文档与根目录遗留的大体积测试安装包，释放 > 22 MB 空间。

---

## [1.5.5] - 2026-08-03
- 合并 sing-box 1.13.14 内核更新与 NaiveProxy 插件升级。
