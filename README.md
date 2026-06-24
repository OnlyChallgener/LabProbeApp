# LabProbe / 极客网探 Android App v0.7.0

原生 Kotlin + Jetpack Compose + Material 3 风格。

## v0.7.0 更新

- DNS Geo 改为优先走 Hub `/api/geo`，支持本地前缀标记、运营商识别、Geo 参考分层显示。
- DNS / 端口 / SSH 输入支持最近 3 条历史记录，点击填入，点 X 删除。
- 长 IPv6 / STUN / WG / DDNS 地址单行横向滑动，点击复制。
- 记录页改为紧凑事件卡片，隐藏开发字段，减少留白。
- SSH 输出不再默认显示 `exit=0`，成功显示“执行成功”，失败才显示 exit code。
- 版本号：0.7.0。

## 编译

上传到 GitHub 仓库根目录后：

Actions → Android APK → Run workflow

产物：`LabProbe-debug-apk/app-debug.apk`

## 推荐配套 Hub

建议配合 `labprobe-hub v0.6.0`，否则 DNS Geo 仍会回退到公网 Geo API。
