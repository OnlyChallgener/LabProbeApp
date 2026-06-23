# LabProbe / 极客网探 Android App

原生 Android 项目，手动刷新读取 `LabProbe Hub`。

## 功能

- 首页：Hub 状态、出口 IP、WireGuard/STUN、DDNS、关注终端概览
- 终端：读取 `/api/devices` 和 `/api/devices?view=online`
- 工具：Ping、DNS/nsLookup、Telnet/TCP 端口测试、SSH 单条命令
- 记录：读取 `/api/events`
- 我的：Hub 地址、APP_TOKEN、白天/黑夜模式

## 默认 Hub 地址

```text
http://192.168.5.46:58443
```

APP_TOKEN 没有硬编码进源码。打开 APP 后进入「我的」页面填写 APP_TOKEN。

## GitHub 编译

1. 创建 GitHub 仓库，例如 `LabProbe`。
2. 把本项目内容上传到仓库根目录。
3. 进入 `Actions`。
4. 选择 `Android APK`。
5. 点击 `Run workflow`。
6. 编译完成后在 Artifacts 下载 `LabProbe-debug-apk`。

## 构建环境

- Gradle 9.5.1
- Android Gradle Plugin 9.2.0
- JDK 17
- compileSdk 36

## 注意

这是 v0.2.0 测试版。当前 APK 是 debug 包，适合自用测试。
公网访问 Hub 时建议走 Lucky HTTPS 反代，不建议直接暴露 Hub 端口。


## v0.2.0 更新
- DNS/nsLookup 支持指定 DNS 服务器：system、223.5.5.5、8.8.8.8 等。
- DNS 支持 A / AAAA / ALL 查询，避免手机代理或私有 DNS 误解析。
- 首页改为以 NAS 出口 IPv4/IPv6 为主，暂不显示路由器 IPv6。
- STUN / WireGuard 地址点击即可复制，不再单独放复制按钮。
- 工具页增加说明文字。
- 记录页隐藏仅包含 token 的无效 Lucky Webhook 测试事件。
- 增加 LabProbe 应用图标。
