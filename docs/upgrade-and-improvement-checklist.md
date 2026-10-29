# 升级与优化改进清单

> 来源：合并 [`project-issues-assessment.md`](project-issues-assessment.md)（项目问题评估）与 sing-box v1.14.4 升级评估。
> 基线：主仓库 `cd0d53d`；sing-box `1.13.14-neko`；上游最新稳定版 `v1.14.4`（`v1.15.0` 仍为 alpha，不纳入）。
> 用法：按阶段顺序推进，每阶段完成后再进入下一阶段。`[ ]` 为待办。

---

## 阶段 0：签名密钥保密，且保持覆盖升级（最优先）

### 0.1 现状（已核实）

- 仓库 `rainsmen/nekoboxi` 是**公开**仓库。
- `keystore/release.keystore` 已提交入库；`Helpers.kt` 的默认值 `storePassword = "android"`、`keyAlias = "thbox"` 能直接打开它。
- 证书信息：别名 `thbox`，RSA 2048，创建于 2026-09-03，SHA-256 指纹：
  `30:03:10:D2:64:48:D4:5E:F8:50:9A:92:6F:36:E5:8D:9F:6E:24:4F:03:9F:81:43:B4:E2:BE:EC:A8:0A:9C:CF`
- 1.5.6、1.5.7 两个 Release 用这个密钥签名。更早的版本用的是 CI 临时生成的随机密钥，那部分用户无论怎样都要卸载重装一次。

结论：**这个私钥和密码已经公开**。从现在起删除文件、改密码，都只能阻止以后继续扩散，无法收回已经泄露的内容（任何人都可能已经克隆或 fork）。

### 0.2 原理：覆盖升级只看两件事

Android 判断"能否覆盖安装"只看：

1. `applicationId` 相同（当前为 `io.github.rainsmen.thbox`，保持不变）；
2. 签名**证书**相同，或新证书在 APK 签名方案 v3 的轮换链（lineage）里被旧证书认可。

keystore 文件的密码、存放位置、是否在仓库里，都和证书无关。所以：

- **修改 keystore 密码不会改变证书**，升级不受影响（`keytool -storepasswd` / `-keypasswd`）。
- 把 keystore 从仓库移到 GitHub Secrets，证书不变，升级不受影响。
- 换成新密钥会改变证书。不做轮换处理的话，用户必须卸载重装。

### 0.3 方案对比

| 方案 | 用户是否需卸载 | 能否消除泄露风险 | 工作量 |
|---|---|---|---|
| A. 原密钥改密码，移入 Secrets | 否 | 否：泄露的旧文件加旧密码仍然有效，别人仍可签出"合法更新" | 小 |
| B. A + v3 密钥轮换到新密钥（推荐） | 否 | 基本可以：设备装过轮换版本后只信任新密钥 | 中 |
| C. 换新密钥，不做轮换 | **是** | 是 | 小 |
| D. 换包名 | **是** | 是 | 小 |

推荐 **方案 B**：minSdk 为 29，所有支持的设备（Android 9 / API 28 起）都支持 v3 签名轮换。

### 0.4 方案 B 实施步骤

**第一步：把密钥移出仓库（同时完成方案 A）**

- [ ] 离线备份当前 `keystore/release.keystore`，存到密码管理器或加密存储。**丢失后所有用户都必须卸载重装。**（已备份到仓库外的 `~/.thbox-signing/old-release.keystore`，**还需另存一份离线备份**）
- [x] 生成新密钥 `thbox-2026.keystore`（RSA 4096 或 EC P-256），设置强随机密码，同样离线备份。新密钥从未进入仓库。（`~/.thbox-signing/new-release.keystore`，别名 `thbox2026`，**还需另存离线备份**）
- [ ] 在 GitHub 仓库 Settings → Secrets 中添加：（**需要手动完成**：名称以 `docs/release-signing.md` 为准（`OLD_ALIAS_NAME`、`ALIAS_NAME`））
  - `OLD_KEYSTORE_BASE64`、`OLD_KEYSTORE_PASS`、`OLD_KEY_ALIAS`、`OLD_KEY_PASS`
  - `KEYSTORE_BASE64`、`KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASS`（新密钥）
  - `SIGNING_LINEAGE_BASE64`（下一步生成的轮换链文件）
- [x] 从仓库删除 `keystore/release.keystore`；删除 `.gitignore` 中的 `!keystore/release.keystore` 例外规则。
- [x] `Helpers.kt`：删除 `"android"` / `"thbox"` 默认值。release 构建缺少签名配置时**直接报错**，不要静默回退到 debug 密钥（回退后产出的 APK 无法覆盖升级）。
- [x] `Helpers.kt`：debug 构建改用独立的 debug 密钥，不再使用 release 密钥（debug 已有 `.debug` 包名后缀，不会和正式版冲突）。
- [x] 本地构建：在已被忽略的 `local.properties` 中配置 `KEYSTORE_*`，或改为支持 `KEYSTORE_FILE=<仓库外路径>`。（支持 `KEYSTORE_FILE` 或 `KEYSTORE_BASE64`）

**第二步：生成轮换链（本地一次性操作，build-tools 35.0.1 自带 `apksigner`）**

```bash
apksigner rotate --out lineage.bin \
  --old-signer --ks release.keystore --ks-key-alias thbox \
  --new-signer --ks thbox-2026.keystore --ks-key-alias thbox2026
base64 -w0 lineage.bin   # 结果存入 SIGNING_LINEAGE_BASE64
```

**第三步：CI 用"旧密钥 + 新密钥 + 轮换链"签名**

AGP 的 `signingConfig` 不支持 lineage，需要在 Gradle 构建出 APK 后用 `apksigner` 重新签名：

```bash
apksigner sign \
  --ks old.keystore --ks-key-alias "$OLD_KEY_ALIAS" --ks-pass env:OLD_KEYSTORE_PASS \
  --next-signer \
  --ks new.keystore --ks-key-alias "$KEY_ALIAS" --ks-pass env:KEYSTORE_PASS \
  --lineage lineage.bin \
  --rotation-min-sdk-version 28 \
  app.apk
```

- `--rotation-min-sdk-version 28` 必须设置。它的默认值是 33，不设的话 Android 9–12 设备不会切换到新密钥。
- [x] 签名后加校验步骤：用 `apksigner verify --print-certs -v` 检查证书链，指纹不符就让 CI 失败。（`rotate_sign.sh` 用 `EXPECTED_CERT_SHA256` 校验）
- [x] job 结束时删除解码出的 keystore 文件。上传的 artifact 只能包含 APK。（keystore 解码到 mktemp 目录，退出时删除；Gradle 的解码文件在 `build/` 中，不在 artifact 里）

**第四步：发布与收尾**

- [ ] 发布第一个轮换版本。用户从 1.5.6 / 1.5.7 直接覆盖安装即可。（配置 Secrets 后执行 release workflow）
- [ ] 以后所有版本都继续携带 lineage。没装过过渡版本、跳版本升级的用户也能正常覆盖。
- [ ] 可选：用 `apksigner lineage` 收回旧密钥的权限（只能在用户普遍升级后做）。
- [ ] 可选：用 `git filter-repo` 从历史中清除 keystore 并强制推送。这一步具有破坏性，只能减少继续扩散，已有的克隆无法收回；执行前需单独确认。

**剩余风险**：还没升级到轮换版本的设备仍然只信任旧密钥，这段时间内有人可以用泄露的密钥签出冒充更新。所以轮换版本要尽快发布，并在 Release 说明里提示用户只从官方 Release 下载。

### 0.5 相关 CI 安全

- [x] `release.yml` 里 `${{ github.event.inputs.tag }}` 直接拼进 shell 脚本。签名 Secrets 进入这个 job 后，脚本注入就可能泄露密钥，因此必须改成 `env: TAG: ${{ ... }}`，再引用 `"$TAG"`。
- [x] 签名 Secrets 只在 release job 中引用；preview 构建可以用单独的预览密钥，或者只有 release 才正式签名。（新密钥和 lineage 只在 `release.yml` 中引用；preview 只用已公开的旧密钥）

---

## 阶段 1：仓库与 CI 整理（升级 sing-box 的前提）

### 1.1 固定 sing-box 版本

- [x] `buildScript/lib/core/get_source_env.sh`：`COMMIT_SING_BOX="1.13.14-neko"` 是分支名，改成固定的提交哈希。（`get_source_env.sh` 已删除，sing-box 由子模块固定到 `0ab4010b0`）
- [x] 合并分叉：CI 实际编译的分支 `1.13.14-neko`（`6301bab`）缺少 `0e6b8a5 feat(naive): add connection warmup`；仓库记录的 `0e6b8a5`（`1.13.x-neko`）缺少 1.13.14 合并带来的 19 个提交。合并后让两边指向同一个提交。（以 v1.14.4 重新 rebase，两个分支的补丁都已包含）
- [x] 子模块二选一：补全 `.gitmodules`，或者从仓库移除 `sing-box`、`libneko` 两个子模块登记（mode 160000），完全依赖 `get_source.sh` 拉取。（补全了 `.gitmodules`）

### 1.2 工作流

- [x] `preview.yml` 的缓存 key 加上 sing-box 版本，与 `release.yml` 保持一致。改用固定哈希后，建议直接对 `COMMIT_SING_BOX` 的值做哈希。（缓存 key 包含 `git ls-files -s sing-box libneko`）
- [x] 删除或修复 `release.yml:128` 的 play job：仓库没有 `.gitmodules`，`git submodule update` 必然失败。同时更正 CHANGELOG 1.5.7 中"已移除"的说法。（已删除；CHANGELOG 已更正）
- [x] `publish` 输入改为布尔类型，语义改为"是否发布"。
- [x] Gradle 缓存 key 加入 `gradle-wrapper.properties`，或改用 `gradle/actions/setup-gradle`。（改用 `gradle/actions/setup-gradle`）

### 1.3 构建脚本

- [x] `download_naive.sh` 只下载实际打包的 ABI（当前只打包 `arm64-v8a`），或者放开 ABI 拆包。
- [x] `get_source.sh` 构建时的补丁（`sed` 修改 cronet 版本、生成 `pidfd_android.go`）改为直接提交到 fork 分支。sing-box `871d070` 已经删除过 `pidfd_android.go`，这个脚本又会重新生成它，两处逻辑相互冲突。（`get_source.sh` 不再打补丁；`pidfd_android.go` 在 1.14.4 上游已恢复（无 build tag），fork 直接保留）
- [ ] `libcore/build.sh`：要不要通过 ldflags 注入 `C.Version`，先读完 3.3 再决定。（暂不注入，见 3.3）

### 1.4 应用安全

- [x] `AndroidManifest.xml:52` 的 `allowBackup="true"`：Android 10/11 上可以用 `adb backup` 导出节点密码。应关闭，或者用 `dataExtractionRules` 把数据库排除在外。（`allowBackup=false`，备份与迁移规则排除数据库）

---

## 阶段 2：在 1.13 上先完成配置迁移（1.13 已支持新格式，风险最低）

### 2.1 DNS 服务器改为新格式（1.14 会直接拒绝旧格式）

文件：`ConfigBuilder.kt`、`SingBoxOptions.java`

- [x] `address = "rcode://success"`（`:702`，`dns-block`）：删除这个服务器，被拦截的域名改用 DNS 规则动作 `predefined`（`rcode: NOERROR`），或者用 `reject`。
- [x] `address = "local"`（`:707`）→ `type: "local"`。
- [x] 用户填写的直连/远程 DNS 字符串（`:713-730`）：编写解析函数，把 `https://`、`tls://`、`quic://`、`h3://`、`tcp://`、`udp://`、纯 IP 等写法转换成 `type` / `server` / `server_port` / `path`，并为每种写法补单元测试。（`DnsServerParser.kt` + `DnsServerParserTest`（9 项））
- [x] `address_resolver`（`:717`、`:727`）→ `domain_resolver`。
- [x] `dns.fakeip = {...}`（`:767`）→ 新建一个 `type: "fakeip"` 服务器，带 `inet4_range` / `inet6_range`。
- [x] `SingBoxOptions.java` 的 `DNSServerOptions` 补充新字段，删除旧字段。

### 2.2 domain strategy 重新设计（容易出错）

- [x] 直连/远程 DNS 服务器上单独配置的 `strategy` 已被删除。DNS 规则上的 `strategy` 在 1.14 也被废弃。（改为在 DNS 规则中用 `query_type` + `predefined` 屏蔽对应记录类型）
- [x] **冲突点**：fakeip 规则用了 `query_type = ["A","AAAA"]`（约 `:784`）。1.14 中 `query_type` 和旧版规则 `strategy` 出现在同一份配置里会**被拒绝启动**。（已不再使用规则 `strategy`）
- [x] 改法：全局策略用 `dns.strategy`；出站解析用 `domain_resolver: {server, strategy}`；出站的 `domain_strategy`（`:449`）也迁移到 `domain_resolver.strategy`。
- [x] 防环路规则 `outbound: ["any"]` → `route.default_domain_resolver`。

### 2.3 WireGuard（1.13 就已失效）

- [x] `box_include.go:88` 已经不再注册 WireGuard 出站，但 `WireGuardFmt.kt:31` 仍生成 `type: "wireguard"` 出站。需要改为生成 `endpoints` 中的 WireGuard endpoint，并调整 `ConfigBuilder.kt:390` 附近的出站组装逻辑。

### 2.4 顺带清理已废弃字段（1.16 删除）

- [x] 删除 `independent_cache = true`（`ConfigBuilder.kt:201`）。
- [x] `store_rdrc` → `store_dns`（`ConfigBuilder.kt:176`）。注意：`store_dns` 要等到阶段 3 升级 1.14 后才能用，1.13 上先保留 `store_rdrc`。
- [x] Hysteria v1 的 `recv_window_conn` / `recv_window` / `disable_mtu_discovery`（`HysteriaFmt.kt:251-253, 290-299`）改用 1.14 的共用 QUIC 参数，同样在阶段 3 完成。

### 2.5 阶段验证（实机）

- [ ] 普通节点（vmess / trojan / vless）、naive、Hysteria2、WireGuard 都能连通。
- [ ] FakeDNS 开启和关闭两种情况下，Chrome 都能正常访问 HTTPS / HTTP3 网站。
- [ ] 分流规则（直连、代理、拦截）命中正确，DNS 没有泄漏。

---

## 阶段 3：升级 sing-box 到 v1.14.4

### 3.1 rebase 定制补丁

本地共有 5 个 neko 提交，约 350 行。上游没有 `boxapi/`、`nekoutils/` 两个目录，这部分不会冲突。

- [x] `2bb2a572a` NekoBox 定制模块（boxapi、nekoutils、selector、`rule_set_local`）
- [x] `000c1f3d0` dialer `DoNotSelectInterface` + vless mux flow（冲突风险最高）
- [x] `b30642b53` 注册 `adapter.PlatformInterface`（Android 禁止 netlink）
- [x] `0e6b8a593` naive 连接预热
- [x] 不再需要 `pidfd_android.go`（1.14.4 上游自带该文件）

### 3.2 适配 Go 接口变化（不改无法编译）

- [x] `libbox.PlatformInterface` 删除了 `UseProcFS()`、`SystemCertificates()`。需要同步修改 `libcore/platform_box.go`，以及 fork 中调用了 `UseProcFS()` 的 `experimental/libbox/nekobox_platform.go`。
- [x] 新增方法在 `platform_box.go` 中先给空实现：`CancelNotification`、`Start/CloseNeighborMonitor`、`RegisterMyInterface`、`UsePlatformShell` / `CheckPlatformShell` / `OpenShellSession`、`LookupUser`、`LookupSFTPServer`、`ReadSystemSSHHostKey`、`TailscaleHostname`、`UsePlatformBridge` / `CreateBridge`。
- [x] 内部 `adapter.PlatformInterface` 的签名变化，例如 `ReadWIFIState(ctx)`。

### 3.3 工具链

- [x] `libcore/go.mod` 的 `go 1.24.7` 改为 `go 1.25.5` 以上（1.14 要求 Go ≥ 1.25）。（`go 1.25.5`；CI 固定 `1.25.x`（Go 1.26+ 编译失败））
- [x] `get_source.sh` 降级 cronet-go 的 `sed` 匹配的是旧版本号 `20260513...`，1.14 用的是 `20260912...`，替换会静默失效。需要重新验证 NDK 27 下的 "relocation 315" 链接错误，再决定保留、更新还是删除这段降级逻辑。（cronet 未链接（未启用 `with_naive_outbound`），降级逻辑已删除）
- [x] `download_naive.sh` 中的 NaiveProxy 从 `v150.0.7871.63-1` 同步到 `-2`。（上游只在最新 release 保留插件 APK，-1 已 404 且不存在 -2；改为 `v154.0.8037.49-5`，sha256 取自 GitHub 资产 digest，需实机验证 naive 节点）
- [ ] 版本号注入：现在 `C.Version = "unknown"`，所有废弃项都只打警告。如果注入 `1.14.x`，"缺少 `domain_resolver`"、出站 `domain_strategy`、`outbound` 规则项都会**直接退出**。必须先完成 2.2，再注入版本号。（暂不注入：注入后废弃项会直接退出，需先在实机上确认生成的配置没有遗留废弃字段）

### 3.4 完成 1.14 才支持的迁移

- [x] `store_rdrc` → `store_dns`
- [x] Hysteria v1 调优字段改用共用 QUIC 参数
- [x] Hysteria2 默认模拟 Chrome QUIC 指纹，服务端使用 Ed25519 证书时握手会失败：在 `HysteriaFmt` 和设置界面中加 `disable_chrome_parrot` 开关。

### 3.5 行为变化验证（实机）

- [ ] TUN `dns_mode`：开启 `auto_route` 时默认是 `hijack`。确认在 Android VpnService 下和现有 `hijack-dns` 路由规则没有重复或冲突，必要时显式设置。（本应用未开启 `auto_route`，1.14 默认 `dns_mode` 不变；仍需实机确认）
- [ ] `query_type` / `ip_version` 现在对内部解析也生效：确认 fakeip 规则不影响 `resolve` 路由动作。
- [ ] 规则集匹配语义修正：回归测试本地 geosite / geoip 规则集的分流结果。

---

## 阶段 4：引入 1.14 新特性（按价值排序）

- [x] **乐观 DNS 缓存**：`dns.optimistic` + `store_dns` + `dns.timeout`。改动小，冷启动和重连收益明显。（`dns.optimistic` + `store_dns`；`timeout` 用默认值）
- [ ] **`package_name_regex`**：路由规则和 DNS 规则直接按包名正则匹配，替代 `PackageCache` 把包名转成 UID 的做法（`ConfigBuilder.kt` 约 `:558`），顺带去掉其中的 `runBlocking`。
- [ ] **`evaluate` / `match_response` / `race`**：按 DNS 应答分流（例如应答 IP 属于 geoip-cn 时走直连 DNS），可以替代固定规则。
- [ ] **Hysteria2 增强**：`gecko` 混淆、`bbr_profile`、`hop_interval_max`、`realm`（NAT 穿透），需要扩展 Bean、设置界面和链接解析。
- [ ] **Snell 出站**：新增 Bean、设置界面和链接/订阅解析。
- [ ] TLS `handshake_timeout`；规则集多 tag 与 `initial_path`（使用远程规则集时）。
- [ ] 低优先级：OpenVPN / OpenConnect endpoint（需要交互式认证界面）。
- 不纳入：API service / Dashboard、USB/IP、netns、`bridge` 出站、TLS spoof（需 root）、Windows/Apple TLS 引擎、Tailscale SSH、`source_mac_address`。

**1.15 预告**（暂不处理）：TUN `stack` 选项会废弃，届时 `ConfigBuilder.kt:223` 和"TUN 实现"设置项需要移除；另外会新增 MASQUE、HTTP 代理 H2/H3/UDP 支持和完整证书固定。

---

## 阶段 5：依赖与代码质量（可与阶段 2–4 并行）

### 5.1 依赖与构建

- [ ] 升级老旧依赖：kotlinx-coroutines 1.6.4、core-ktx 1.9.0、navigation 2.5.3、fragment-ktx 1.5.6、gson 2.9.0、guava 31.0.1、kryo 5.2.1，并统一 AndroidX 版本。
- [ ] 引入 version catalog（`gradle/libs.versions.toml`）。
- [ ] 去掉 `android.enableJetifier=true`，评估开启 `nonTransitiveRClass`。
- [x] `Helpers.kt` 中 `tasks.create` 改为 `tasks.register`。
- [ ] lint：在 CI 中加入 lint 任务，或者去掉不起作用的 `warningsAsErrors = true`。

### 5.2 测试与代码结构

- [ ] 优先为阶段 2 的 DNS URL 解析和 `ConfigBuilder` 输出写单元测试，再覆盖 `V2RayFmt.kt` 等链接解析、`RawUpdater.kt` 订阅解析。（DNS 解析已有测试；`ConfigBuilder` 输出与链接解析测试待补）
- [ ] 拆分大文件：`ConfigurationFragment.kt`（1746 行）、`ConfigBuilder.kt`（826 行）、`RawUpdater.kt`（810 行）、`V2RayFmt.kt`（698 行）。
- [ ] 替换 29 处 `GlobalScope` / `runBlocking`，改用 lifecycle 或应用级 scope。
- [ ] 9 处吞掉异常或只调用 `printStackTrace` 的地方改为统一写日志。

---

## 附：文档维护

- [x] `docs/singbox-latest-upgrade-and-optimization-assessment-2026-09.md` 基于 1.14.0，缺少 3.2、2.2 中的冲突点和 3.3 的内容，需要补充或在文中注明以本清单为准。（文首已注明以本清单为准）
- [x] `docs/README.md` 索引加入本清单和 `project-issues-assessment.md`。
