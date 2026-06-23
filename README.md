# LabProbe / 极客网探 v0.4.1

Kotlin + Jetpack Compose + Material 3 风格重构版。

## 主要改动

- 改为 Kotlin + Jetpack Compose UI。
- 工具页改成左侧功能名 + 右侧输入框，不再堆超长输入框。
- Hub 连接使用 OkHttp 自定义 DNS，默认 `223.5.5.5`，优先 IPv6，并过滤 `127.0.0.1`。
- DNS 工具支持 `system / 223.5.5.5 / 8.8.8.8`，支持 `A / AAAA / ALL`。
- 首页只显示 NAS 出口 IPv4 / IPv6，路由器 IPv6 暂时隐藏。
- STUN / WG 地址点击即复制，不再单独放复制按钮。
- SSH 采用 mwiede JSch，并开启 ssh-rsa / 老 KEX / 老 cipher 兼容。
- 记录页过滤 token-only 的错误 Lucky Webhook 事件。

## 默认设置

- Hub: `http://192.168.5.46:58443`
- Hub DNS: `223.5.5.5`
- APP_TOKEN: 需要在“我的”页面填写。

## GitHub Actions 编译

上传到 GitHub 后：

`Actions -> Android APK -> Run workflow`

完成后下载 `LabProbe-debug-apk`。


## v0.4.1
- SSH 按锐捷实测算法调整：ssh-rsa host key、curve25519-sha256@libssh.org、aes256-ctr、hmac-sha2-256。
- 增加 password / keyboard-interactive 登录兼容。
