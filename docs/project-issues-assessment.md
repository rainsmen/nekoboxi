# 项目问题与优化评估

> 范围：主仓库构建配置、CI 工作流、构建脚本、`app` 模块代码与 Manifest。仅评估，未做修改。
> 评估基线：主仓库 `cd0d53d`，sing-box 子模块 `0e6b8a5`（`1.13.x-neko`）。

总体结论：最严重的两个问题是**签名密钥提交在仓库中**，以及 **CI 实际编译的 sing-box 版本与仓库记录的版本不一致**。

---

## 一、高优先级

### 1. 签名密钥提交在仓库里，且有默认密码

- `keystore/release.keystore` 在提交 `178c113` 中被提交入库。
- `buildSrc/src/main/kotlin/Helpers.kt` 在未配置密码时回退到 `storePassword = "android"`、`keyAlias = "thbox"`。
- 影响：如果仓库公开，或实际密码就是默认值，任何人都能签出与正式版签名相同的 APK，并作为"更新"覆盖安装到用户设备上。
- `.gitignore` 规则互相矛盾：先写了 `!keystore/release.keystore`，末尾又写了 `keystore/release.keystore`。
- debug 构建也使用 release 密钥签名，扩大了泄露面。

**建议**：把 keystore 移出仓库，只通过 `KEYSTORE_BASE64` secret 注入，删除默认密码回退。若密钥已公开，应考虑换新密钥。debug 构建改用独立的 debug 密钥。

### 2. CI 编译的 sing-box 与仓库记录的不是同一版本

- `buildScript/lib/core/get_source_env.sh` 中 `COMMIT_SING_BOX="1.13.14-neko"` 是**分支名**，远端当前指向 `6301bab`。
- 主仓库中 `sing-box` 记录的提交是 `0e6b8a5`（`1.13.x-neko` 分支）。
- 两者已经分叉：
  - CI 编译的版本缺少 `0e6b8a5 feat(naive): add connection warmup`。
  - 仓库记录的版本缺少 1.13.14 合并带来的 19 个提交。
- 用分支名做版本，构建结果不可复现。分支更新后 libcore 缓存不会失效，因为缓存 key 哈希的是仓库记录的提交，而 CI 编译时用的不是它。

**建议**：`COMMIT_SING_BOX` 改成固定的提交哈希，并与仓库记录的提交保持一致。先把 naive warmup 提交合入 1.13.14 分支，或者反过来合并。

### 3. 子模块配置不完整

- `sing-box`、`libneko` 以子模块形式（mode `160000`）登记在仓库中，但仓库没有 `.gitmodules` 文件。所有 `git submodule` 命令都会失败，新克隆的仓库里这两个目录是空的。
- `release.yml` 的 play job 执行 `git submodule update --init 'app/*'`，运行时必然失败。
- `CHANGELOG.md` 1.5.7 写着"移除 `Build Play Bundle`"，但 `release.yml:128` 中该 job 仍然存在，只是由 `play == 'true'` 控制是否运行。

**建议**：二选一：补全 `.gitmodules`，或者从仓库移除子模块登记、完全依赖 `get_source.sh` 拉取源码。删除或修复 play job，并更正 CHANGELOG。

---

## 二、中优先级

### 4. CI 与构建脚本

| 问题 | 位置 | 建议 |
|---|---|---|
| preview 的缓存 key 没有包含 `git ls-files -s sing-box`，与 release 不一致 | `.github/workflows/preview.yml` | 两个工作流统一缓存 key 计算方式 |
| 构建时用 `sed` 修改 sing-box 的 `go.mod`（降级 cronet-go），并重新生成 `pidfd_android.go`；而 sing-box `871d070` 正是为了 gomobile 兼容性才删掉这个文件 | `buildScript/lib/core/get_source.sh` | 把补丁直接提交到 fork 分支，构建时不动态修改源码 |
| 下载 3 个 ABI 的 naive，但 ABI 拆包只打包 `arm64-v8a` | `download_naive.sh`、`Helpers.kt` | 只下载需要的 ABI，或放开 ABI 拆包 |
| 输入 `publish` 为 `'y'` 时反而跳过发布，语义与直觉相反 | `release.yml` | 改为布尔类型输入，语义改为"是否发布" |
| `tag` 输入通过 `${{ }}` 直接拼入 shell 脚本，有脚本注入风险（只有有写权限的人能触发，风险低） | `release.yml` | 通过 `env:` 传入再引用 `"$TAG"` |
| Gradle 缓存 key 只对 `*.gradle.kts` 哈希，没有包含 `gradle-wrapper.properties` | 两个工作流 | key 中加入 wrapper 配置；或改用 `gradle/actions/setup-gradle` |

### 5. 依赖与构建配置老旧

- 多数依赖停留在 2022–2023 年：kotlinx-coroutines 1.6.4、core-ktx 1.9.0、navigation 2.5.3、fragment-ktx 1.5.6、gson 2.9.0、guava 31.0.1、kryo 5.2.1。AndroidX 各库版本不统一。
- 没有使用 version catalog（`gradle/libs.versions.toml`），版本号散落在 `app/build.gradle.kts`、`buildSrc/build.gradle.kts`、根 `build.gradle.kts` 中。
- `android.enableJetifier=true` 大概率已不需要，会拖慢构建；`android.nonTransitiveRClass=false` 也会增加编译时间。
- `Helpers.kt` 中使用 `tasks.create(...)`（急切创建），应改为 `tasks.register(...)`。

### 6. 应用安全

- `AndroidManifest.xml:52` 设置 `android:allowBackup="true"`，minSdk 为 29。在 Android 10/11 上，节点密码、订阅链接可以通过 `adb backup` 导出。

**建议**：关闭 `allowBackup`，或配置 `dataExtractionRules` / `fullBackupContent`，把数据库与配置排除在外。

---

## 三、低优先级（代码质量）

- **没有测试**：`app/src` 下没有单元测试或仪器测试。链接解析（`V2RayFmt.kt` 等）和订阅解析（`RawUpdater.kt`）是输入多变、最容易回归的部分，最适合先补测试。
- **大文件**：`ConfigurationFragment.kt` 1746 行，`ConfigBuilder.kt` 826 行，`RawUpdater.kt` 810 行，`V2RayFmt.kt` 698 行，建议按职责拆分。
- **协程用法**：`GlobalScope` 与 `runBlocking` 共 29 处，有泄漏和阻塞主线程的风险，应改用 `lifecycleScope` / `viewModelScope` 或应用级 scope。
- **异常处理**：9 处吞掉异常或只调用 `printStackTrace`，问题难以排查，建议统一走日志。
- **lint 配置基本没起作用**：`Helpers.kt` 设置了 `warningsAsErrors = true`，但 CI 只运行 assemble，只会执行 lintVital。要么在 CI 中加 lint 任务，要么去掉这个配置。

---

## 四、建议处理顺序

1. 签名密钥移出仓库，删除默认密码回退（第 1 项）。
2. 固定 sing-box 版本为提交哈希，统一子模块记录与构建版本（第 2、3 项）。
3. 整理 CI：统一缓存 key、移除构建时补丁、修正 play job 与发布输入（第 4 项）。
4. 关闭 `allowBackup` 或配置备份排除规则（第 6 项）。
5. 逐步升级依赖并引入 version catalog，再补测试与拆分大文件（第 5 项及第三节）。
