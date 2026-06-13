# 🚀 如何启用 Naive 连接预热优化

## 📱 在 UI 中启用（推荐）

新版本已经添加了 UI 界面支持，无需手动编辑配置文件！

### 操作步骤：

1. **打开应用**并进入配置界面
2. **点击你的 Naive 节点**进入编辑界面
3. **向下滚动**到底部 "sing-box server" 分类
4. **找到 "Connection Warmup" 开关**
5. **打开开关**（会显示说明：预先建立连接以减少首次请求延迟）
6. **保存**配置
7. **重新连接** VPN

### UI 界面示意：

```
┌─────────────────────────────────────┐
│  sing-box server                    │
├─────────────────────────────────────┤
│  UDP over TCP          [OFF]        │
├─────────────────────────────────────┤
│  Connection Warmup     [ON] ← 开启这个
│  Pre-establish connection on        │
│  startup to reduce first request    │
│  latency (recommended)              │
└─────────────────────────────────────┘
```

## ✅ 验证是否生效

启用后，在日志中会看到：

```
INFO  NaiveProxy started, version: xxx
DEBUG warming up connection to proxy server...
INFO  connection warmup completed successfully
```

如果看到这些日志，说明预热功能已经成功启动！

## 🎯 测试效果

### 对比测试方法：

**测试 A（未启用）**：
1. 关闭 "Connection Warmup" 开关
2. 完全关闭应用
3. 重新启动并连接 VPN
4. 立即访问 x.com，记录加载时间：_____ 秒

**测试 B（已启用）**：
1. 打开 "Connection Warmup" 开关
2. 完全关闭应用
3. 重新启动并连接 VPN
4. 立即访问 x.com，记录加载时间：_____ 秒

**预期结果**：测试 B 应该比测试 A 快 **50% 以上**

## 💡 常见问题

### Q1: 开关在哪里？
**A**: 在 Naive 节点编辑界面的最底部，"sing-box server" 分类下。

### Q2: 日志在哪里查看？
**A**: 
1. 进入应用设置
2. 将日志级别设置为 "Debug"
3. 连接 VPN 后查看日志输出

### Q3: 开启后感觉没变化？
**A**: 
- 确保日志中有 "connection warmup completed successfully"
- 确保测试的是**首次访问**（冷启动）
- 5G 网络下效果最明显

### Q4: 会增加流量吗？
**A**: 几乎没有影响，预热连接只消耗约 1KB 流量。

### Q5: 会影响启动速度吗？
**A**: 不会，预热是后台异步进行的，不会阻塞应用启动。

### Q6: 需要重新安装吗？
**A**: 不需要，直接安装新版 APK 即可（会自动覆盖更新）。

## 📥 下载测试版本

编译完成后，从这里下载：
https://github.com/rainsmen/nekoboxi/actions

选择最新的 "Preview Build"，下载 APKs 文件。

## 🐛 遇到问题？

如果遇到任何问题，请在 PR 中反馈：
https://github.com/rainsmen/nekoboxi/pull/1

包括：
- 设备型号
- Android 版本
- 网络类型（5G/4G/WiFi）
- 日志截图（如果可能）
- 问题描述

---

**祝使用愉快！** 🎉
