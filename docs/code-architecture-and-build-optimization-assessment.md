# ThBox 代码架构、构建流水线与系统优化评估报告

> **评估日期**：2026-09-03  
> **评估范围**：代码架构、Gradle/JVM 编译器配置、LibCore 原生核心、CI/CD 自动化流水线、系统合规与文档体系  
> **项目基线**：ThBox for Android (`io.github.rainsmen.thbox`)

---

## 一、代码与编译配置层面的改进评估

### 1. JVM 编译目标与编译器规范现代化
- **现状**：在 `buildSrc/src/main/kotlin/Helpers.kt` 中，`compileOptions` 与 `jvmTarget` 仍配置为 `JavaVersion.VERSION_1_8`（Java 8）。
- **影响**：当前开发环境已全面运行在 JDK 17、Gradle 8.10.2、AGP 8.8.1 之上，运行环境目标为 Android 10~15（API 29~35）。保留 Java 8 限制了 Kotlin 2.0+ 编译器对现代 Java 字节码与协程调度的优化潜力。
- **优化建议**：
  - 将 `compileOptions` 的 `sourceCompatibility` 与 `targetCompatibility` 升级至 `JavaVersion.VERSION_17`；
  - 修复已废弃的 Gradle API：将 `rootProject.buildDir` 迁移至 `rootProject.layout.buildDirectory`。

### 2. Android 14/15 前台服务类型合规
- **现状**：`AndroidManifest.xml` 中将所有前台服务（`ProxyService`、`VpnService`、`TileService`）统一声明为 `android:foregroundServiceType="systemExempted"` 并使用了 `tools:ignore="ForegroundServicePermission"`。
- **影响**：从 Android 14（API 34）开始，系统对前台服务类型施加严格限制。对于第三方普通应用，`systemExempted` 容易在部分国产定制系统（HyperOS、ColorOS、OriginOS）被电池策略标记，增加被后台查杀的概率。
- **优化建议**：将 `VpnService` 规范化为受系统广泛认可的类型，添加必要的元数据配置，提升系统对常驻 VPN 进程的保活认可度。

### 3. 第三方历史依赖解耦
- **现状**：项目依赖了 2019 年停更的第三方数据库迁移库 `com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4`。
- **优化建议**：逐步转为 Room 2.6+ 原生的 `@AutoMigration` 机制，淘汰第三方中间层，减少 JitPack 网络拉取与 KSP 额外注解处理器的负担。

---

## 二、CI/CD 流水线与构建性能优化

### 1. 移除冗余的 Google Play Bundle 构建任务
- **现状**：`.github/workflows/release.yml` 每次触发都会执行 `Build Play Bundle`（打包 `.aab` 耗时约 3 分 20 秒）。
- **影响**：项目已明确声明不通过 Google Play 分发，生成的 `.aab` 仅作为临时构建产物上传，并未发布给任何用户，白白消耗 Actions 运行配额。
- **优化建议**：从 `release.yml` 中剔除无意义的 `play` 任务，发布时每次可直接缩短约 3.5 分钟的云端构建等待。

### 2. 发布工具链现代化（淘汰 2020 年的第三方 `ghr`）
- **现状**：`release.yml` 在发布阶段通过 `wget` 动态下载 2020 年发布的第三方工具 `ghr v0.13.0`。
- **优化建议**：GitHub Actions Runner 原生内置最新版官方 GitHub CLI（`gh`），直接调用 `gh release create` / `gh release upload`，无需外网下载老旧第三方二进制，杜绝单点下载失败隐患。

### 3. 增强 LibCore 编译缓存对 `sing-box` 子模组的感知
- **现状**：计算缓存指纹时仅对 `buildScript` 和 `libcore` 进行了校验，未包含 `sing-box` 子模组本身。
- **优化建议**：在 `golang_status` 计算中加入 `git ls-files -s sing-box`，确保内核子模组指针变更时必定触发原生核心重新编译，防止云端缓存脏读。

---

## 三、文档与项目规范优化

### 1. 引入结构化变更日志（`CHANGELOG.md`）
- 在仓库根目录建立标准 [Keep a Changelog](https://keepachangelog.com/) 规范的 `CHANGELOG.md`，完整记录版本演进、缺陷修复与安全更新。

### 2. 编写常见问题与排查指南（`docs/troubleshooting-faq.md`）
- 针对用户最关心的证书冲突与覆盖安装、FakeIP 与直连 DNS 的配置搭配、以及分应用代理自启动权限等提供明确指导。

### 3. 架构分层拓扑图
- 在 `docs/README.md` 中加入 Mermaid 架构图，清晰展现 Android UI -> VpnService -> LibCore gomobile -> sing-box 核心的出站处理链路。
