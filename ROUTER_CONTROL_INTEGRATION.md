# LabProbe Router Control v0.10.11 / build 141

目标分支：`codex/router-control-v01011-build141`

本分支包括：

- 工具箱页新增路由器功能横滑卡片
- 映射与 UPnP 三分页：IPv6映射 / 端口映射 / UPnP
- DDNS + 证书监控、防火墙、网络自检
- 路由器连接设置与 Hub 模拟登录
- APP `0.10.11 build141`
- 配套 Hub `0.9.8`、LabRelay `0.2.9`

## 页面位置

路由器功能卡片放在 **工具箱页 `ToolsHomeScreen`**，不是工业页，也不改工业设备页面。

工具箱新增一行可横向滑动的小卡片：

```text
映射与UPnP｜DDNS｜防火墙｜网络自检
```

标题右侧的小齿轮进入“路由器连接”。原工具箱的卡片、分组、排序和跳转保持不变。

## 路由器密码流程

用户在 APP 的“路由器连接”页面自行输入：

```text
管理地址
管理密码
会话超时 600-7200 秒
```

保存流程：

```text
APP 输入密码
→ 发送给 Hub
→ Hub 保存并测试模拟登录
→ 后续 Hub 自动维护 sid/stok 会话
```

用户不需要输入密钥、查看密文或执行解密。Hub 内部的安全保存和自动读取只是实现细节。

已保存密码时，编辑页面留空表示保持原密码；需要修改时重新输入新密码即可。

## 路由

```kotlin
"tool_portmap" -> MappingAndUpnpScreen(prefs, backFromTool)
"tool_router_ddns" -> RouterDdnsScreen(prefs, backFromTool)
"tool_router_firewall" -> RouterFirewallScreen(prefs, backFromTool)
"tool_router_diag" -> RouterDiagnosticScreen(prefs, backFromTool)
"tool_router_login" -> RouterLoginSettingsScreen(prefs, backFromTool)
```

## 页面行为

- IPv6映射：复用原 `PortMappingScreen`，原功能和布局尽量不改。
- 端口映射：路由器原生 IPv4 NAT；新增/编辑用底部大弹出页。
- UPnP：显示总开关、默认线路和只读动态映射。
- DDNS：复杂配置使用紧凑全屏页，和证书监控合并为两个页签。
- 防火墙：支持转发/入站/出站、IPv4/IPv6/双栈、协议、启停和删除。
- 网络自检：启动后每秒同步进度，达到 100% 自动停止。
- 所有写操作：Hub 写入后重新 GET 验证，再更新 APP 页面。

## 终端列表

终端页保持原布局，只增加：

```text
实时 ↑120 Kbps ↓1.26 Mbps · 连接 26
```

字段：

```text
realtimeUpBytes
realtimeDownBytes
connectionCount
```

终端 IPv6 继续由 LabRelay 邻居数据按 MAC 合并。

## 获取分支

APP：

```bash
cd D:\Github\LabProbeApp
git fetch origin
git checkout codex/router-control-v01011-build141
git pull origin codex/router-control-v01011-build141
```

Hub / LabRelay：

```bash
cd D:\Github\labprobe-hub
git fetch origin
git checkout codex/router-rpc-v098-relay-v029
git pull origin codex/router-rpc-v098-relay-v029
```

## 构建 APP

```bash
gradle :app:assembleRelease --stacktrace
```

APK：

```text
app/build/outputs/apk/release/app-release.apk
```

也可以在 GitHub Actions 下载：

```text
LabProbe-v0.10.11-build141-release-apk
```
