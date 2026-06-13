# Naive Outbound 性能优化实施说明

## 变更概述

本次优化针对 Naive outbound 的冷启动延迟问题进行改进，主要实施了连接预热机制。

## 问题背景

### 性能问题
根据日志分析（log/0001.log vs log/0002.log），发现：
- **冷启动延迟**：首次连接需要 1.75-1.84 秒完成 HTTP/2 握手
- **用户体验**：打开网站（如 x.com）时明显慢于原版 NekoBox
- **根本原因**：所有请求通过单一 HTTP/2 长连接复用，首次需要完整握手

### 架构差异
- **本项目**：Native Naive 使用 cronet-go，所有流量通过 HTTP/2 长连接
- **原版**：libnaive 插件通过 SOCKS5 接口，连接管理更优化

## 实施的优化

### 1. 连接预热机制

#### 代码变更
**文件**: `sing-box/protocol/naive/outbound.go`

**新增字段**:
```go
type Outbound struct {
    // ... existing fields
    connectionWarmup bool  // 是否启用连接预热
}
```

**新增方法**:
```go
func (h *Outbound) warmupConnection()
```

**功能**:
- 在服务启动阶段（`Start()`）后台预建立连接
- 通过发起一个到 `www.google.com:443` 的连接来触发 HTTP/2 握手
- 连接建立后短暂保持（100ms）然后关闭
- 实际用户请求到来时可以复用已建立的 HTTP/2 连接

**预期效果**:
- 减少首次请求的延迟从 ~1.8s 降低到 <500ms
- 不影响后续请求性能（本就通过连接复用）

#### 配置选项
**文件**: `sing-box/option/naive.go`

**新增字段**:
```go
type NaiveOutboundOptions struct {
    // ... existing fields
    ConnectionWarmup bool `json:"connection_warmup,omitempty"`
}
```

**使用方式**:
```json
{
  "type": "naive",
  "connection_warmup": true,
  ...
}
```

**默认值**: `false`（向后兼容，不改变现有行为）

### 2. 优化建议配置

推荐配置项（需要在应用层实现）：
```json
{
  "type": "naive",
  "connection_warmup": true,
  "insecure_concurrency": 4,
  "quic": true,
  "quic_congestion_control": "bbr"
}
```

**参数说明**:
- `connection_warmup`: 启用连接预热（本次实现）
- `insecure_concurrency`: HTTP/2 并发流数量，增加可提高并发性能
- `quic`: 使用 QUIC 协议（HTTP/3），可能进一步改善性能
- `quic_congestion_control`: 使用 BBR 拥塞控制算法

## 技术细节

### 连接预热流程
```
1. 用户启动 VPN
2. Naive Outbound.Start() 被调用
3. cronet engine 启动
4. [新增] 如果启用 connection_warmup:
   a. 后台 goroutine 启动
   b. 发起到 www.google.com:443 的连接
   c. 触发 HTTP/2 CONNECT 到代理服务器
   d. 等待 100ms
   e. 关闭测试连接
5. 用户首次请求到达时，复用已建立的 HTTP/2 连接
```

### 时序对比

**优化前**:
```
用户请求 -> DNS解析 -> TCP握手 -> TLS握手 -> HTTP/2协商 -> 发送数据
                       [------------ ~1.8s ------------]
```

**优化后**:
```
启动阶段(后台): DNS解析 -> TCP握手 -> TLS握手 -> HTTP/2协商
                [----------- ~1.8s -----------]
                                                    
用户请求: 复用已有连接 -> 发送数据
          [---- <100ms ----]
```

## 测试建议

### 测试场景
1. **冷启动测试**
   - 启动 VPN 后立即访问 x.com
   - 对比开启/关闭 connection_warmup 的加载时间
   
2. **稳定性测试**
   - 长时间保持 VPN 连接（30分钟+）
   - 观察是否有连接中断

3. **并发测试**
   - 同时打开多个网页标签
   - 测试并发性能

### 性能指标
- **首次请求延迟**: 预期从 ~1.8s 降低到 <500ms
- **后续请求延迟**: 应保持不变（<200ms）
- **连接稳定性**: 不应增加连接中断率

## 局限性

### 已知限制
1. **预热失败处理**: 如果预热失败（网络问题等），不影响正常使用，只是首次请求仍会有延迟
2. **额外流量**: 预热会产生少量额外流量（<1KB）
3. **架构限制**: 由于使用 HTTP/2 协议特性，某些场景仍可能无法完全达到原版性能

### 未来优化方向
1. **连接池管理**: 维护多个活跃连接以提高并发性能
2. **智能预热**: 根据使用模式动态调整预热策略
3. **连接保活**: 实现更主动的 PING 机制减少连接中断
4. **快速失败**: 改进连接故障检测和自动重试

## 回滚方案

如果发现问题，可以通过以下方式回滚：
1. 设置 `connection_warmup: false`（或完全不配置该字段）
2. 行为将与之前版本完全一致

## 相关文件

### 修改的文件
1. `sing-box/protocol/naive/outbound.go` - 核心实现
2. `sing-box/option/naive.go` - 配置选项

### 新增的文件
1. `docs/naive-performance-optimization.md` - 优化方案文档
2. `docs/naive-optimized-config-example.json` - 配置示例
3. `docs/naive-optimization-implementation.md` - 本文件

## 参考资料

- [日志分析](log/0001.log) - 本项目性能日志
- [原版日志](log/0002.log) - 原版 NekoBox 性能日志
- [cronet-go](https://github.com/sagernet/cronet-go) - Chromium 网络栈 Go 绑定
- [RFC 7540](https://tools.ietf.org/html/rfc7540) - HTTP/2 规范
