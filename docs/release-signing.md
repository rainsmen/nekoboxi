# 发布签名与密钥轮换

仓库里不再保存签名密钥和密码。发布签名由 GitHub Secrets 提供，并用 APK Signature Scheme v3 密钥轮换从旧密钥过渡到新密钥。已安装的旧版可以直接覆盖升级，不需要卸载。

## 原理

Android 能否覆盖安装只看两件事：`applicationId`（`io.github.rainsmen.thbox`）和签名证书。

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

可选：在 Variables 中添加 `EXPECTED_CERT_SHA256`（新证书 SHA-256），`rotate_sign.sh` 会检查签名结果。

如果密钥别名的密码和 keystore 密码不同，再加 `ALIAS_PASS` / `OLD_ALIAS_PASS`，并在 workflow 中映射。（当前两把密钥的别名密码都与 keystore 密码相同，不需要。）

**注意**：preview 构建只读取 `OLD_*` 三个 Secret。preview 能正式签名，只说明旧密钥配置正确，不代表新密钥的 Secret 也配好了。

## 各构建的签名行为

| 构建 | 未配置 Secrets | 只配置 `OLD_*` | 全部配置 |
|---|---|---|---|
| Preview（推送 `feat/**` 或手动触发） | debug 密钥，不能覆盖正式版 | 旧密钥 | 旧密钥（新密钥只在 release 中使用） |
| Release（手动触发） | Gradle 报错退出 | `rotate_sign.sh` 报错退出 | 旧 → 新轮换 |
| 本地 `assembleOssRelease` | 报错，除非设置 `ALLOW_DEBUG_SIGNING=1` | — | — |

本地发布构建可以在 `local.properties`（已被 gitignore）中写入 `KEYSTORE_FILE=/绝对路径/xxx.keystore`、`KEYSTORE_PASS`、`ALIAS_NAME`。

## 发布流程

1. `nb4a.properties` 中 `VERSION_CODE` 加 1，`VERSION_NAME` 改为新版本号，更新 `CHANGELOG.md`，合并到 `main`。
2. Actions → Release Build → Run workflow，分支选 `main`，Tag 填版本号（如 `1.6.0`），勾选 publish。
3. 发布后可用 `apksigner verify -v --print-certs` 检查：应显示 v3 签名，证书为新证书；`apksigner lineage --in <apk> --print-certs` 应列出旧、新两个证书。

## 常见错误

| 报错 | 原因 | 处理 |
|---|---|---|
| `Failed to load signer "signer #2": ... entry "" does not contain a key` | `ALIAS_NAME` Secret 不存在（日志中显示为空而不是 `***`）。常见原因：加到了 Variables 或 Environment，或名称拼错 | 在 Repository secrets 中添加 `ALIAS_NAME` = `thbox2026` 后重新运行 |
| `rotate_sign.sh` 提示证书不符 | `EXPECTED_CERT_SHA256` 与新证书不一致 | 检查新 keystore 与 Variable 的值 |
| 预览 APK 无法覆盖安装正式版 | `OLD_*` Secret 缺失，预览 APK 用了 debug 密钥 | 检查 `OLD_KEYSTORE_BASE64`、`OLD_KEYSTORE_PASS`、`OLD_ALIAS_NAME` |

## 首次生成（已完成，留作记录）

```bash
keytool -genkeypair -keystore new-release.keystore -alias thbox2026 \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=ThBox, O=rainsmen"
buildScript/sign/make_lineage.sh   # 生成 signing-lineage.bin
```

## 备份

- 旧 keystore、新 keystore、新密码和 `signing-lineage.bin` 都必须离线备份至少两份。丢失新密钥后只能再做一次轮换（需要新密钥 + lineage），两者都丢失则用户必须卸载重装。
- 不要把以上文件提交到仓库。`.gitignore` 已忽略 `/keystore/` 和 `signing-lineage.bin`。
- 旧密钥仍存在于 git 历史中。是否用 `git filter-repo` 清理历史需要另行决定，清理不影响已发布的 APK。
