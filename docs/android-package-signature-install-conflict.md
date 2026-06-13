# Android 安装证书冲突说明

## 结论

当前 GitHub Actions 编译出来的 APK 显示名称和文件名已经不是 NekoBox；要让它与已安装的 NekoBox/NB4A 共存，关键是最终 `applicationId` 不能继续使用原包名。

根因是 Android 判断应用身份使用的是 `applicationId`/包名，不是应用显示名称、图标或 APK 文件名。

本项目当前配置已改为：

```properties
PACKAGE_NAME=io.github.rainsmen.thbox
```

`buildSrc/src/main/kotlin/Helpers.kt` 会读取 `nb4a.properties` 里的 `PACKAGE_NAME`，并设置为最终 APK 的 `applicationId`。因此当前 ThBox 的安装身份是 `io.github.rainsmen.thbox`，不会被 Android 当作 `moe.nb4a` 的更新包。

如果把 `PACKAGE_NAME` 改回 `moe.nb4a`，而手机上已经安装了同包名的 NekoBox/NB4A，Android 会把新 APK 当作更新包处理；GitHub Actions 产物使用不同签名证书时，系统会拒绝安装，并提示类似“更新的软件证书与源软件不同”。

## 可选方案

### 方案一：让 ThBox 与 NekoBox 共存

`nb4a.properties` 保持 fork 专属包名：

```properties
PACKAGE_NAME=io.github.rainsmen.thbox
```

包名应使用自己唯一的反向域名格式，例如：

```properties
PACKAGE_NAME=io.github.yourname.thbox
```

同时全局搜索硬编码的旧包名 `moe.nb4a`，至少需要同步处理：

- `app/src/main/res/xml/shortcuts.xml` 里的 shortcut 目标包名
- 其他直接拼接或比较 `moe.nb4a` 的位置

`app/build.gradle.kts` 里的 `namespace = "io.nekohasekai.sagernet"` 不等于安装包名，通常不需要为了共存而修改。

### 方案二：让 ThBox 覆盖升级原 NekoBox

必须满足两个条件：

- `applicationId` 继续保持与原应用一致，例如 `moe.nb4a`
- 使用与原已安装 NekoBox 完全相同的签名证书

如果原 NekoBox 是官方发布版本，通常拿不到官方签名私钥，因此正常情况下不能直接覆盖升级。

只有原手机上安装的旧包也是自己用同一个 keystore 构建的，才可以继续用同一证书升级。

### 方案三：仅本机安装，不要求共存

可以先在原 NekoBox 内导出/备份配置，然后卸载原 NekoBox，再安装 GitHub Actions 产物。

这种方式会避开签名冲突，但 Android 会按卸载规则清理原应用数据，后续需要手动恢复备份。

## 验证点

后续修改前后重点检查：

- APK 最终 `package`/`applicationId` 是否为 `io.github.rainsmen.thbox`
- APK 签名证书 SHA-256 是否与已安装应用一致
- `shortcuts.xml` 等资源中是否仍硬编码旧包名
- GitHub Actions 使用的 keystore 是否稳定且可复用

显示名称、图标、APK 文件名都不会决定 Android 是否把它当成同一个应用。
