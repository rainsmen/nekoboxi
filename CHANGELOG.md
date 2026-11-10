# 更新日志 (Changelog)

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/) 规范，记录所有重要演进。

---

## [Unreleased]

- 编辑保存在事务内检查恢复代次，默认规则初始化失败整体回滚。
- 订阅 JSON 提交前校验真实核心配置，完整连接参数参与去重；修正固定节点路由和 Naive IP 映射。
- Hysteria 扩展配置导出保留、备份分享失败反馈、资产下载状态随页面重建保留。
- 同名资产替换确认、分组删除范围及无障碍名称完善；构建支持校验本地下载输入，CI 签名测试依赖缺失即失败。


- 编辑器固定对象身份、草稿归属和恢复代次；修复查看引用后误删其他节点及迟到初始化覆盖草稿；关联编辑前明确确认未保存内容。
- Tailscale 真实 endpoint 路由与多实例状态隔离；停止和重启请求带独立 token 与期限。
- sing-box 订阅标签改名保留身份；修复格式关键字误判、SOCKS4a/凭据往返、WireGuard 默认 MTU；分组导出保存独立快照。
- 正式签名校验旧→新完整历史链，版本递增二次检查；修复复制成功误报、旧流量残留和资产清理重试文字。

- 备份拒绝内嵌节点截断及尾随字节；订阅严格拒绝部分解析结果，并在同步下载返回及提交前检查取消。
- 后台订阅失败保存时间与脱敏原因，分组可查看和重试；流量定期保存，停止失败可从主界面重试保存与清理。
- Preview 切换为独立专用签名，最终 APK 校验解析编译资源并检查包内规则资产哈希；旧 Preview 的迁移见签名指南。

- 备份完整校验后跨节点/设置数据库原子恢复；订阅与恢复使用独立 ATTACH 连接，避免污染普通编辑和选择事务。
- v2 规则单独备份附带引用指纹，兼容旧整数及中文集合；部分恢复清除不可靠选择；导出快照在页面重建后继续可用。
- 订阅拒绝空结果和歧义身份，保留可确认改名与重名节点的 ID、自定义配置，阻止破坏链和路由引用；恢复使在途更新失效。
- 编辑保存检测并发变更，测速和排序只写各自字段；修复页面重建后的脏状态、监听器、删除失败提示和未修改路由保存。
- 最终流量在 IO 线程事务提交；失败任务与计数跨 Service 重建保留并可重试；停止状态清零与新启动通过维护锁互斥。
- 完整重启等待成功停止与后台退出；运行锁阻止维护期间重建启动；URL 测速加入核心关闭和计数屏障。
- 修复共享节点统计、selector 归属、链嵌套顺序、分组前置/落地链、内部标签冲突及同毫秒清零。
- 补齐路由条件与规则集注册，保留 DNS 来源条件和仅 DNS 协议规则，修正嗅探、DNS 劫持及组播规则顺序。
- 修复默认网卡监听、DNS 完成/取消、IPv6 socket 地址、包名/UID 快照和纯代理前台服务类型。
- HTTP 增加期限、正文限制、竞速清理；日志写入与导出脱敏。
- 补齐 Hysteria2 扩展参数及分享往返、Hysteria v1 二进制认证分享、Snell v6 YAML、TUIC 固定 IP 的 SNI 和 Clash v2ray-plugin TLS 导入。
- sing-box 出站导入保留线性 detour 内嵌副本，支持查看和编辑；生成配置重写标签，不能安全拆分时明确失败。
- 资产导入/更新统一格式校验与原子替换，版本信息失败单独提示；修复删除、刷新与撤销竞争。
- 固定工具链、子模块、资产校验和与 Actions 提交；修正签名密码映射，新增工作流静态检查、核心启动集成测试及构建证据。
- Preview 使用独立 `.preview` 包名与变体快捷方式，可与正式版共存。
- Material 3、21 套浅深调色板、动态配色、选中态、编辑器对比度、48dp 操作按钮和边距适配。
- 改进恢复确认、错误来源导航、保存动作、更新间隔和资产状态提示；自动验证与实机验证分别记录。

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
  - 更正：本版本当时记录了移除 `Build Play Bundle`，但该任务实际仍存在，直到 1.6.0 才删除；原记载的 40% / 3.5 分钟收益没有有效测量依据，撤回。
  - 淘汰 2020 年的第三方发布工具 `ghr`，全面拥抱 GitHub Actions 原生自带的官方 `gh` CLI；
  - 增强 LibCore 编译缓存键，将 `sing-box` 子模组 commit 纳入哈希计算，杜绝内核缓存脏读。
- **JVM 编译规范现代化**：
  - 将 `compileOptions` 源码与目标兼容级别平滑升级至 Java 17；
  - 修复 Gradle 8.10+ 中废弃的 `rootProject.buildDir` API 调用。
- **文档体系完善**：
  - 新增《系统与代码架构优化评估报告》（`docs/archive/code-architecture-and-build-optimization-assessment.md`，已归档）；
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
