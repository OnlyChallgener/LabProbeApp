# LabProbe Router Control v0.10.11 / build 141

目标分支：`codex/router-control-v01011-build141`

本分支已经加入：

- `RouterControlApi.kt`：Hub 0.9.8 路由器白名单 API 客户端
- `RouterControlUi.kt`：商业级紧凑手机页面、Canvas 小图标、映射与 UPnP 横向分页、防火墙、DDNS+证书、网络自检、路由器连接设置
- APP 版本：`0.10.11`，build `141`

## 1. 接入 MainActivity 路由

在 `MainActivity.kt` 的 route `when` 中，将原来的：

```kotlin
"tool_portmap" -> PortMappingScreen(prefs, backFromTool)
```

替换为：

```kotlin
"tool_portmap" -> MappingAndUpnpScreen(prefs, backFromTool)
"tool_router_ddns" -> RouterDdnsScreen(prefs, backFromTool)
"tool_router_firewall" -> RouterFirewallScreen(prefs, backFromTool)
"tool_router_diag" -> RouterDiagnosticScreen(prefs, backFromTool)
"tool_router_login" -> RouterLoginSettingsScreen(prefs, backFromTool)
```

这些 route 都以 `tool_` 开头，现有 BackHandler 会自动按工具页逻辑返回。

## 2. 工业/工具页加入路由器功能横滑卡片

在 `ToolsHomeScreen` 原有内容中选择一个合适位置加入：

```kotlin
var routerFirewallEnabled by remember { mutableIntStateOf(0) }
var routerDdnsHealthy by remember { mutableIntStateOf(0) }
var routerMappingCount by remember { mutableIntStateOf(0) }
var routerUpnpEnabled by remember { mutableStateOf(false) }
var routerDiagnosticErrors by remember { mutableIntStateOf(0) }

LaunchedEffect(prefs.hub, prefs.token, prefs.hubDns) {
    val api = RouterControlApi(prefs)
    runCatching { api.firewall() }.onSuccess {
        routerFirewallEnabled = it.rules.count { rule -> rule.enabled }
    }
    runCatching { api.ddns() }.onSuccess {
        routerDdnsHealthy = it.count { record ->
            record.enabled && !record.status.contains("error", true) && !record.status.contains("fail", true)
        }
    }
    runCatching { api.nativePortMappings() }.onSuccess {
        routerMappingCount = it.size
    }
    runCatching { api.upnp() }.onSuccess {
        routerUpnpEnabled = it.enabled
        routerMappingCount += it.mappings.size
    }
    runCatching { api.diagnostic() }.onSuccess {
        routerDiagnosticErrors = it.errorCount
    }
}

RouterFeatureRail(
    firewallEnabled = routerFirewallEnabled,
    ddnsHealthy = routerDdnsHealthy,
    mappingCount = routerMappingCount,
    upnpEnabled = routerUpnpEnabled,
    diagnosticErrors = routerDiagnosticErrors,
    onMapping = { onOpen("tool_portmap") },
    onDdns = { onOpen("tool_router_ddns") },
    onFirewall = { onOpen("tool_router_firewall") },
    onDiagnostic = { onOpen("tool_router_diag") }
)
```

保持工业页原卡片和布局不改；只新增这一行横向滑动的小卡片。

## 3. 设置页加入路由器连接入口

在设置页合适位置增加入口：

```kotlin
SettingsActionRow(
    title = "路由器连接",
    subtitle = "Hub 模拟登录 · 600-7200 秒会话",
    icon = Icons.Rounded.Router,
    onClick = { onNavigate("tool_router_login") }
)
```

如果现有设置页没有 `SettingsActionRow`，可直接使用原项目已有的可点击设置卡片，只需要路由到 `tool_router_login`。

## 4. 终端列表数据

原终端 UI 不重构。Hub 的新接口 `/api/router/devices` 已返回：

```text
realtimeUpBytes
realtimeDownBytes
connectionCount
```

把它们映射到现有 `DeviceItem` 后，只在终端卡片追加一行：

```text
实时 ↑120 Kbps ↓1.26 Mbps · 连接 26
```

终端 IPv6 仍由现有 Relay 邻居数据按 MAC 合并。

## 5. 页面行为

- 映射与 UPnP：顶部 `IPv6映射 / 端口映射 / UPnP`，点击和左右滑动均可切换。
- IPv6 映射：复用原 `PortMappingScreen`，尽量不改旧业务。
- 原生端口映射：底部大弹出页新增/编辑；删除使用中部确认。
- UPnP：只提供总开关、默认线路、动态映射只读列表。
- DDNS：独立页面，和原证书到期功能合并为两个页签。
- 防火墙：紧凑全屏编辑，支持增删改、启停、入站/出站/转发、IPv4/IPv6/双栈、TCP/UDP/ICMP/ANY。
- 网络自检：1 秒轮询，100% 后停止。

## 6. 构建

```bash
git fetch origin
git checkout codex/router-control-v01011-build141
gradle :app:assembleRelease --stacktrace
```

生成位置：

```text
app/build/outputs/apk/release/app-release.apk
```

GitHub Actions 会上传：

```text
LabProbe-v0.10.11-build141-release-apk
```
