# STUN 与 Agent 控制链路修复报告（2026-08-30）

## 发布范围

- APP：`0.10.69`（build `224`）
- Hub：`0.12.1`
- LabRelay：`0.2.32`
- 测试标签：发布时写入 `test-bundle/20260830-8`

## 调查结论

### 路由设置页面刷新变慢

Relay 的 Agent 控制循环串行处理状态、STUN、WireGuard、更新和清理命令。此前每条 STUN upsert 会同步等待最多 30 秒的首次公网映射确认，本地控制端又为该请求保留 70 秒读取窗口。多条 STUN 规则连续同步时，后续所有路由状态和 Agent 指令都会排队，因此不只 STUN 页面，其他路由设置页面也会显得刷新很慢。

### STUN 状态反复或长期校验

DNS、TCP connect、Binding 响应超时属于远端网络瞬时错误，却被放在规则创建/更新的同步确认窗口中。关闭再开启后偶尔成功，是新一轮连接刚好在 30 秒窗口内取得映射，并不是配置本身发生了改变。

### 检查更新与一键清理

这些功能和 STUN 共用 Agent 控制循环，因此会被排队。APP 对清理状态轮询中的 `connection closed` 还会立即终止并直接显示英文，造成“突然不可用”的观感。截图中的“立即更新”灰色则是当前版本与最新版本相同后的正常状态。

## 修复内容

1. STUN 规则只在本地应用阶段等待：配置校验、端口冲突、socket/listener bind 和持久化失败仍立即返回并回滚。
2. DNS、远端 TCP 连接、STUN Binding 超时改由既有运行时在后台有限重试，不再占用 Agent 控制循环。
3. STUN 新配置未确认时保持 `starting/mapping`，最近一次公网地址只作为历史地址保留，不作为新配置 ready 的依据。
4. APP 的版本检查和清理状态轮询可容忍短暂连接关闭；清理和 STUN 页面错误统一经中文转换显示。
5. 设置页仅将两处用户可见的 `DeepSeek` 文案改为通用 `AI`，没有改动模型配置、服务商或接口语义。

## 验证结果

- Hub 相关回归：`42 passed`
- Hub 全量 Python 回归：`554 passed`
- 两个仓库 `git diff --check`：通过
- APP 本地未搭建 Android/Gradle 测试环境；APK 编译、Hub 镜像和 LabRelay Rust 构建由 GitHub 测试发布流水线验证。

## 明确未改动

- Router Core、WireGuard、Hub/APP API 契约
- AI 服务商与模型调用逻辑
- IPv6 mapping 和其他路由页面的数据语义
- 一键清理的实际清理范围

