# sing-box 原生 NaiveProxy outbound 评估

## 结论

可以改成 sing-box 原生 NaiveProxy outbound，但不建议直接在 `main` 上一次性切换。

原因是这不是简单删除插件的问题。当前 sing-box 1.13 已经有原生 `type: naive` outbound，Android 平台也在官方支持范围内；但该实现依赖 `cronet-go`，需要在 `libcore` 构建时启用 `with_naive_outbound`。历史文档已多次记录，启用该标签会重新引入 `R_AARCH64_PREL32` / relocation 315 一类 native 链接风险，这是之前 CI 失败的主要根因。

当前建议是单独分支做 POC：先验证 `libcore` 全量构建和真机运行，再决定是否合入 `main` 替换外部插件方案。

## 当前状态

- `sing-box/protocol/naive/outbound.go` 已提供原生 Naive outbound，但受 `with_naive_outbound` build tag 控制。
- `libcore/box_include.go` 已保留 `registerNaiveOutbound(registry)` 调用。
- `libcore/box_include_naive_stub.go` 在未启用 `with_naive_outbound` 时将注册函数变成空实现。
- `libcore/build.sh` 当前没有启用 `with_naive_outbound`，注释明确写明是为了避开 cronet/NDK relocation 链接问题。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/naive/NaiveFmt.kt` 已有 `buildSingBoxOutboundNaiveBean()`，可以生成 `type: naive` 配置。
- `ProxyEntity.needExternal()` 当前对 `TYPE_NAIVE` 固定返回 `true`，所以实际运行路径仍然是外部插件。
- CI 仍通过 `download_naive.sh` 从 MatsuriDayo 插件 release 抽取 `libnaive.so` 并打包进 APK。

## 原生方案需要的改动范围

1. `libcore/build.sh` 重新加入 `with_naive_outbound`。
2. 确认 `libcore` 全量构建通过，重点验证 `cronet-go`、Go 1.25、gomobile 和 Android NDK 27 的组合。
3. App 侧让 `TYPE_NAIVE` 不再走 external path，使 `ConfigBuilder` 进入 `buildSingBoxOutboundNaiveBean()`。
4. 移除或保留但不使用 `BoxInstance` 中 Naive 插件启动逻辑。
5. CI 去掉 `download_naive.sh` 步骤，APK 不再打包 `libnaive.so`。
6. 补齐原生 Naive 配置映射：当前 `proto == "quic"` 没有写入 sing-box 的 `quic: true`，因此 `naive+quic` 原生路径会不完整。
7. 复核 `udp_over_tcp`、ECH、证书、extra headers、insecure concurrency 等字段在原生路径下的 JSON 输出和运行行为。

## 风险点

- 最大风险是 native 链接，而不是配置生成。历史记录显示，`with_naive_outbound` + `cronet-go` 曾导致 CI 在 libcore 构建阶段失败。
- libcore cache 命中时不能证明原生 Naive 可构建，必须触发一次完整 Native Build。
- 原生 Naive 不再需要插件进程的 mapping/protect workaround，但仍需要真机确认 Android VPN/TUN 下 outbound dialer 的 socket protect 行为。
- sing-box 原生 Naive 支持的 TLS 字段有限：官方文档说明只支持 `server_name`、`certificate`、`certificate_path` 和 `ech`，不支持 insecure、uTLS、TLS fragment、Reality 等高级 TLS 选项。
- 当前插件方案只打包 `arm64-v8a` 的 `libnaive.so`；原生方案理论上能统一进 libcore，但前提是各 ABI 的 cronet 链接都能稳定通过。

## 建议验证路线

1. 在 `feature/native-naive-poc` 分支做最小 POC。
2. 第一阶段只做构建闭环：启用 `with_naive_outbound`，触发 GitHub Actions，确认 `Native Build (LibCore)` 不是缓存命中而是完整构建成功。
3. 第二阶段切 App 路径：让 Naive 走 internal outbound，并补 `quic` / `udp_over_tcp` 等映射。
4. 第三阶段真机验证：
   - `naive+https` 可连接、可测速、可上网。
   - `naive+quic` 正确下发 `quic: true` 并可连接。
   - 自定义证书节点能握手。
   - VPN 模式下不会出现 TUN 回环。
   - 链式代理、URLTest、重启服务后行为正常。
5. 全部通过后，再删除插件下载和启动路径，并合入 `main`。

## 当前建议

保持 `main` 上的外部插件方案不动。原生 Naive 作为单独分支推进，只有在 CI 全量 libcore 构建和真机回归都通过后再同步到 `main`。
