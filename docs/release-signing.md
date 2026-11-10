# 发布签名与密钥轮换

仓库里不再保存签名密钥和密码。发布签名由 GitHub Secrets 提供，并用 APK Signature Scheme v3 密钥轮换从旧密钥过渡到新密钥。已安装的旧版可以直接覆盖升级，不需要卸载。

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

必须在 Variables 中添加 `EXPECTED_CERT_SHA256`（新证书 SHA-256，可带冒号）。Release 在编译前检查该指纹配置，`rotate_sign.sh` 再核对实际签名结果；缺失或不匹配会失败。Preview 不使用这个正式发布门禁。

如果密钥别名的密码和 keystore 密码不同，再加 `ALIAS_PASS` / `OLD_ALIAS_PASS`，Gradle 使用旧密钥时把 `OLD_ALIAS_PASS` 映射为 `ALIAS_PASS`，后续轮换步骤分别传入两者，未设置时回退到对应 keystore 密码。（当前两把密钥的别名密码都与 keystore 密码相同。）

**注意**：本分支 Preview 使用独立包名 `io.github.rainsmen.thbox.preview`，与正式版共存。CI 签名环境变量优先于 properties；云端 Preview 映射 `OLD_*`，缺失时失败，以保持不同构建之间的覆盖安装身份；不使用新发布私钥。本地未配置签名的 Preview 使用本机 debug 证书，不能保证跨机器覆盖安装。Preview 构建成功不能证明 Release 的签名配置完整。

## 各构建的签名行为

| 构建 | 未配置 Secrets | 只配置 `OLD_*` | 全部配置 |
|---|---|---|---|
| Preview（独立包名，推送 `feat/**` 或手动触发） | 前置校验失败 | 旧密钥，与正式版共存 | 旧密钥，与正式版共存 |
| Release（手动触发） | 前置校验失败 | 前置校验失败 | 测试、构建、旧 → 新轮换、可选发布 |
| 本地 `assembleOssRelease` | 报错，除非设置 `ALLOW_DEBUG_SIGNING=1` | — | — |

本地发布构建可以在 `local.properties`（已被 gitignore）中写入 `KEYSTORE_FILE=/绝对路径/xxx.keystore`、`KEYSTORE_PASS`、`ALIAS_NAME`。

## 发布流程

1. `nb4a.properties` 中 `VERSION_CODE` 加 1，`VERSION_NAME` 改为新版本号，更新 `CHANGELOG.md`，合并到 `main`。
2. Actions → Release Build → Run workflow，分支选 `main`，Tag 填版本号（如 `1.6.0`），勾选 publish。
3. `publish=false` 只构建并保留 Actions artifact，不创建 Release。已有 tag 必须指向当前构建提交；已存在 Release 不覆盖，使用新版本号。
4. Release 附件同时包含 APK 与 `build-evidence.txt`，后者记录构建提交、子模块和 APK SHA-256；核对时使用同一 Release 的证据。
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
