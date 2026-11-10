# 发布签名与密钥轮换

仓库里不再保存签名密钥和密码。发布签名由 GitHub Secrets 提供，并用 APK Signature Scheme v3 密钥轮换从旧密钥过渡到新密钥。已安装且使用已知正式证书的 1.5.6、1.5.7 可覆盖升级至正确轮换签名的版本；1.5.5 及更早的随机签名构建需要先备份再迁移。独立 Preview 的迁移另见下文。

## 原理

覆盖安装首先要求包名与签名兼容，同时还受版本码、设备 ABI 和系统版本等安装条件影响。签名相关的两个要素是：`applicationId`（`io.github.rainsmen.thbox`）和签名证书。

- 旧证书（`thbox`，SHA-256 `30:03:10:D2:…:9C:CF`）的私钥和密码已经在仓库历史中公开，只能作为过渡使用。
- 新证书（`thbox2026`，SHA-256 `E7:75:B3:99:31:0D:35:84:14:96:20:91:7B:B4:FA:C3:91:AA:C9:6A:E3:96:BA:66:E4:FE:2A:7A:DC:76:7D:7B`）只存放在 GitHub Secrets 和离线备份中。
- `signing-lineage.bin` 是旧密钥对新密钥的授权证明。用旧密钥加 `--next-signer` 新密钥和 lineage 签名后，APK 的 v3 签名中带有信任链：旧证书 → 新证书。装着旧版的设备据此认可新证书，安装一次后就只认新证书。
- 应用最低支持 Android 10（minSdk 29），所有设备都支持 v3 签名，因此发布的 APK 只含 v3 签名。

1.6.0 是第一个轮换版本（2026-10-30 发布），已用 `apksigner` 确认签名链正确，并实测可从旧版直接覆盖安装。

### 包名与共存

Android 只用包名（`applicationId`）区分应用，显示名称、图标和 APK 文件名都不影响。ThBox 的包名是 `io.github.rainsmen.thbox`（`nb4a.properties` 的 `PACKAGE_NAME`），与 NekoBox / NB4A（`moe.nb4a`）不同，所以两者可以同时安装，ThBox 也无法覆盖升级 NekoBox。不要修改 `PACKAGE_NAME`，否则已安装的用户都必须卸载重装。

AGP 不支持带 lineage 签名，所以 CI 中 Gradle 先用旧密钥签名，再由 `buildScript/sign/rotate_sign.sh` 重签。

## 需要配置的 Secrets

在仓库 Settings → Secrets and variables → Actions 中添加：

| 名称 | 内容 |
|---|---|
| `OLD_KEYSTORE_BASE64` | `base64 -w0 old-release.keystore` 的输出 |
| `OLD_KEYSTORE_PASS` | 旧 keystore 密码 |
| `OLD_ALIAS_NAME` | `thbox` |
| `KEYSTORE_BASE64` | `base64 -w0 new-release.keystore` 的输出 |
| `KEYSTORE_PASS` | 新 keystore 密码 |
| `ALIAS_NAME` | `thbox2026` |
| `SIGNING_LINEAGE_BASE64` | `base64 -w0 signing-lineage.bin` 的输出 |

必须在 Variables 中添加 `EXPECTED_CERT_SHA256`（新证书 SHA-256，可带冒号）。Release 在编译前检查该指纹配置，`rotate_sign.sh` 再核对实际签名结果；缺失或不匹配会失败。轮换前还校验 lineage 文件，轮换后及最终 APK 门禁再次提取历史链，要求恰好为固定旧证书 `300310D26448D45EF8509A926F36E58D9F6E244F039F8143B4E2BEECA80A9CCF` → 该新证书；只含新签名、没有历史链的 APK 不通过正式发布门禁。Preview 不使用这个正式发布门禁。

如果密钥别名的密码和 keystore 密码不同，再加 `ALIAS_PASS` / `OLD_ALIAS_PASS`，Gradle 使用旧密钥时把 `OLD_ALIAS_PASS` 映射为 `ALIAS_PASS`，后续轮换步骤分别传入两者，未设置时回退到对应 keystore 密码。（当前两把密钥的别名密码都与 keystore 密码相同。）

本分支 Preview 使用独立包名 `io.github.rainsmen.thbox.preview`，与正式版共存。Preview 采用专用签名身份，不再映射公开过的旧正式版密钥，也不使用新的正式版私钥。

Preview 需要单独配置：

| 名称 | 类型 | 内容 |
|---|---|---|
| `PREVIEW_KEYSTORE_BASE64` | Secret | 专用预览 keystore 的 Base64 |
| `PREVIEW_KEYSTORE_PASS` | Secret | 预览 keystore 密码 |
| `PREVIEW_ALIAS_NAME` | Secret | 预览密钥别名 |
| `PREVIEW_ALIAS_PASS` | 可选 Secret | 别名密码；省略时使用 keystore 密码 |
| `EXPECTED_PREVIEW_CERT_SHA256` | Variable | 专用预览证书 SHA-256 |

## 各构建的签名行为

| 构建 | 所需配置 | 行为 |
|---|---|---|
| 云端 Preview（推送 `feat/**` 或手动触发） | `PREVIEW_*` 与预览证书指纹 | 缺少配置即失败，最终 APK 必须匹配预览指纹 |
| Release（手动触发） | 旧/新正式密钥、lineage 与正式证书指纹 | 测试、构建、旧 → 新轮换、可选发布 |
| 本地 Preview | 可选本地签名配置 | 未配置时使用本机 debug 证书，不能保证跨机器覆盖安装 |
| 本地 `assembleOssRelease` | 正式签名配置 | 缺少配置时报错；`ALLOW_DEBUG_SIGNING=1` 仅用于明确选择的本地验证 |

### 旧 Preview 的迁移

2026-11 分支将 Preview 切换为专用证书。同样带 `.preview` 包名、但由旧密钥或另一台机器 debug 密钥签名的 APK，不能依靠包名相同直接覆盖。请先在旧 **ThBox Preview** 内导出备份，确认备份文件已保存，再仅卸载旧 Preview，安装新 Preview 后恢复。正式版 ThBox 是另一应用，不需要卸载。以后同一专用证书的 Preview 构建可保持签名身份。

Preview 构建成功不能证明 Release 的签名配置完整。APK 最终校验还会检查包名、最低系统版本、ABI、快捷方式资源、规则资产版本及解压后的哈希。

本地发布构建可以在 `local.properties`（已被 gitignore）中写入 `KEYSTORE_FILE=/绝对路径/xxx.keystore`、`KEYSTORE_PASS`、`ALIAS_NAME`。

签名按整套来源选择：环境变量提供 `KEYSTORE_FILE` 或 `KEYSTORE_BASE64` 时，密码和别名也必须来自环境变量，不混用本地配置；否则整套读取本地属性。同一来源不能同时设置文件和 Base64；指定文件不存在时直接失败。别名密码未指定时回退到同一来源的 keystore 密码。

## 发布流程

1. `nb4a.properties` 中 `VERSION_CODE` 加 1，`VERSION_NAME` 改为新版本号，更新 `CHANGELOG.md`，合并到 `main`。
2. Actions → Release Build → Run workflow，分支选 `main`，Tag 填版本号（如 `1.6.0`），勾选 publish。
3. `publish=false` 只构建并保留 Actions artifact，不创建 Release。已有 tag 必须指向当前构建提交；已存在 Release 不覆盖，使用新版本号。
4. 所有正式发布共用并发锁。发布前重新获取标签，并再次校验标签与 `VERSION_NAME`、当前提交和递增 `VERSION_CODE`；不同标签并发不能绕过该检查。Release 附件同时包含 APK 与 `build-evidence.txt`，后者记录构建提交、子模块和 APK SHA-256；核对时使用同一 Release 的证据。
5. 发布后可用 `apksigner verify -v --print-certs` 检查：应显示 v3 签名，证书为新证书；`apksigner lineage --in <apk> --print-certs` 应列出旧、新两个证书。

## 常见错误

| 报错 | 原因 | 处理 |
|---|---|---|
| `Failed to load signer "signer #2": ... entry "" does not contain a key` | `ALIAS_NAME` Secret 不存在（日志中显示为空而不是 `***`）。常见原因：加到了 Variables 或 Environment，或名称拼错 | 在 Repository secrets 中添加 `ALIAS_NAME` = `thbox2026` 后重新运行 |
| `rotate_sign.sh` 提示证书不符 | `EXPECTED_CERT_SHA256` 与新证书不一致 | 检查新 keystore 与 Variable 的值 |
| Preview 没有覆盖正式版 | 当前 Preview 包名带 `.preview`，设计为共存安装 | 分别打开两款应用，使用应用内备份迁移数据 |
| 旧 Preview 无法覆盖 1.6.0 正式版 | 旧 Preview 同包名但只用旧密钥，正式版已轮换证书；也可能是 debug 签名 | 使用当前独立包名 Preview，不要通过卸载正式版试错 |

## 首次生成（已完成，留作记录）

```bash
keytool -genkeypair -keystore new-release.keystore -alias thbox2026 \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=ThBox, O=rainsmen"
buildScript/sign/make_lineage.sh   # 生成 signing-lineage.bin
```

## 备份

- 旧 keystore、新 keystore、新密码和 `signing-lineage.bin` 都必须离线备份至少两份。若只是本地文件丢失，应从离线备份恢复。再次轮换需要当前签名私钥及有效的 lineage；当前私钥彻底丢失后，lineage 或公开旧密钥不能替代它，不能承诺保留覆盖升级能力。
- 不要把以上文件提交到仓库。`.gitignore` 已忽略 `/keystore/` 和 `signing-lineage.bin`。
- 旧密钥仍存在于 git 历史中。是否用 `git filter-repo` 清理历史需要另行决定，清理不影响已发布的 APK。


## 离线输入与 F-Droid 范围

F-Droid 任务生成未签名 arm64 APK，供分发方签名；当前 Naive 能力依赖锁定的上游插件 APK，不宣称整个产物只由本仓库源码重建或已通过任何分发平台审查。

在线准备时设置 `THBOX_DOWNLOAD_CACHE`，资产脚本会保存已经通过 SHA-256 校验的输入。离线时使用同一目录并设置 `THBOX_OFFLINE=1`：

- 规则输入文件名为 `geoip-<GEOIP_VERSION>.db`、`geosite-<GEOSITE_VERSION>.db`，版本及哈希来自 `buildScript/lib/assets.lock`。
- Naive 输入文件名为 `naiveproxy-plugin-<VERSION>-arm64-v8a.apk`，版本及哈希来自 `download_naive.sh`。保留原 APK，不能只提供提取后的 `.so` 跳过校验。
- 固定子模块提交、Go 模块与构建工具、Android SDK/NDK、Gradle 依赖必须提前就绪。预构建在离线模式禁用 Go 下载，子模块使用 `--no-fetch`；Gradle 另加 `--offline`。
- 缺少输入或哈希不符会明确失败。此模式解决已准备输入的离线使用，不提供 Naive 的源码编译替代品；需要全源码产物的分发流程仍须另行构建并审查该组件。

Preview/Release 在运行签名集成测试前显式安装 SDK 35 和 Build Tools 35.0.1；CI 缺少这些工具会失败，只有本地开发允许跳过依赖不全的集成测试。
