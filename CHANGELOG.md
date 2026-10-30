# 更新日志 (Changelog)

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/) 规范，记录所有重要演进。

---

## [Unreleased]

---

## [1.6.0] - 2026-10-30

### 变更 (Changed)
- 内核升级到 sing-box v1.14.4（`rainsmen/singbox` 分支 `1.14.4-neko`），以 git submodule 固定提交；libcore 要求 Go 1.25.x。
- DNS 配置迁移到 1.14 的类型化服务器格式（`type` / `server` / `domain_resolver`），`rcode://` 拦截改为 `predefined` 规则动作，ipv4_only / ipv6_only 通过 `query_type` 规则实现；启用乐观 DNS 缓存与 `store_dns`。
- WireGuard 改为 endpoint；Hysteria v1 调优字段改用共用 QUIC 参数；Hysteria2 新增“禁用 Chrome QUIC 指纹”开关。
- 签名密钥移出仓库，改由 GitHub Secrets 提供，Release 使用 APK v3 密钥轮换，已安装版本可直接覆盖升级（见 `docs/release-signing.md`）。
- 关闭 `allowBackup`，系统备份与设备迁移不再包含节点数据库。
- `release.yml`：删除无法运行的 Play Bundle job（1.5.7 记录中写了移除，但当时 job 仍在），`publish` 改为布尔输入，tag 通过环境变量传入。
- `download_naive.sh` 只下载实际打包的 `arm64-v8a`；NaiveProxy 插件更新到 `v154.0.8037.49-5`（上游已删除 `v150.0.7871.63-1` 的资产，旧版本无法下载）。
- 新增 `DnsServerParser` 单元测试，CI 中运行。

### 新增 (Added)
- Snell 出站（v4 / v6），支持 Clash 订阅解析；数据库版本升至 7（自动迁移）。
- Hysteria2：`gecko` 混淆、`bbr_profile`、`hop_interval_max`、`realm`（NAT 穿透）。
- “智能国内 DNS”开关：A/AAAA 先查直连 DNS，应答属于 geoip:cn 时直接采用（`evaluate` / `match_response`）。

### 修复 (Fixed)
- 分应用代理改为按包名（`package_name`）匹配。此前选中的应用全部未安装时 `user_id` 为空，规则会扩大到所有应用。

### 内部 (Internal)
- 引入 version catalog，升级 coroutines、AndroidX、WorkManager、Guava、Kryo 等依赖（Gson 因序列化器兼容问题保持 2.9.0）；去掉 Jetifier，开启 `nonTransitiveRClass`。
- libcore 注入 sing-box 版本号；`GlobalScope` 改为应用级 scope；`printStackTrace` 改为写日志。
- CI 增加 lint、Kotlin 构建器测试、Kryo 兼容测试和 Go 端 1.14 配置校验。

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
