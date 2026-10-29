# 发布签名与密钥轮换

仓库里不再保存签名密钥和密码。发布签名由 GitHub Secrets 提供，并用 APK Signature Scheme v3 密钥轮换从旧密钥过渡到新密钥。已安装的旧版可以直接覆盖升级，不需要卸载。

## 原理

Android 能否覆盖安装只看两件事：`applicationId`（`io.github.rainsmen.thbox`）和签名证书。

- 旧证书（`thbox`，SHA-256 `30:03:10:D2:…:9C:CF`）的私钥和密码已经在仓库历史中公开，只能作为过渡使用。
- 新证书（`thbox2026`，SHA-256 `E7:75:B3:99:31:0D:35:84:14:96:20:91:7B:B4:FA:C3:91:AA:C9:6A:E3:96:BA:66:E4:FE:2A:7A:DC:76:7D:7B`）只存放在 GitHub Secrets 和离线备份中。
- `signing-lineage.bin` 是旧密钥对新密钥的授权证明。用旧密钥加 `--next-signer` 新密钥和 lineage 签名后：
  - Android 9（API 28）及以上按 v3 签名验证，信任链为旧证书 → 新证书。安装一次后，设备就只认新证书。
  - Android 8.1 及以下按 v1/v2 签名验证，看到的仍是旧证书，可以继续升级。

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

如果密钥别名的密码和 keystore 密码不同，再加 `ALIAS_PASS` / `OLD_ALIAS_PASS`，并在 workflow 中映射。

## 各构建的签名行为

| 构建 | 未配置 Secrets | 只配置 `OLD_*` | 全部配置 |
|---|---|---|---|
| Preview（推送 `feat/**` 或手动触发） | debug 密钥，不能覆盖正式版 | 旧密钥 | 旧密钥（新密钥只在 release 中使用） |
| Release（手动触发） | Gradle 报错退出 | `rotate_sign.sh` 报错退出 | 旧 → 新轮换 |
| 本地 `assembleOssRelease` | 报错，除非设置 `ALLOW_DEBUG_SIGNING=1` | — | — |

本地发布构建可以在 `local.properties`（已被 gitignore）中写入 `KEYSTORE_FILE=/绝对路径/xxx.keystore`、`KEYSTORE_PASS`、`ALIAS_NAME`。

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
