# LabProbe / 极客网探 Android APP v0.6.0

Kotlin + Jetpack Compose + Material 3 Expressive 风格。

## 本版重点

- 首页重做为更紧凑的家庭网络仪表盘。
- 前台自动刷新下拉：手动 / 3S / 10S / 30S；失败不清空旧数据。
- 工具页改为二级页面：Ping / DNS / 端口探测 / SSH。
- Ping 默认 20 次，间隔 30 / 100 / 200 / 500 / 1000ms；采样可高频，UI 约 1 秒刷新，避免闪烁。
- DNS 支持双 DNS、A / AAAA / ALL 下拉、Geo 匹配。
- 端口探测支持 TCP / UDP，域名优先 AAAA / IPv6。
- SSH 保留密码开关、默认命令 `ip -6 neigh show`。
- 首页显示 NAS IPv4 / IPv6、路由 WAN IPv6、WG `[NAS IPv6]:51820`、STUN。
- 新图标：无文字、无白边、全尺寸圆角图形。

## GitHub 编译

上传到仓库根目录后运行：Actions → Android APK → Run workflow。
