# Labprobe v0.9.15 hotfix5

版本号：v0.9.15  
versionCode：50

## 本次重点

- 工具页网络状态卡改为 6 项：IPv4 出口地址、IPv6 地址、NAT 类型、运营商、本地 IP、优先级。
- NAT 类型胶囊可点击进入 NAT 检测页，网络状态卡右上角增加刷新按钮。
- 端口测试与 UDP 探测拆成独立页面：端口测试只做 TCP Connect，UDP 探测支持 STUN / DNS / NTP / UDP 空包模板。
- NAT 检测新增双模式：RFC5780 / STUN RFC8489 行为发现、RFC3489 TEST 1-4 传统检测。
- NAT 默认服务器：RFC5780 使用 `stun.voip.aebc.com:3478`，RFC3489 使用 `stun.miwifi.com:3478`。
- 每种 NAT 模式最多保存 10 个服务器，可删除、恢复默认；测试失败会按服务器列表顺序重测。
- NAT 记录页新增：最多保存 50 条，显示日期时间、服务器、NAT 类型、映射地址、本地 IP、IPv6、优先级，支持左滑删除。
- 页面返回使用 APP 内路由：NAT 记录返回 NAT 检测，NAT 检测返回工具页，不直接回桌面。

## 注意

- IPv6 不硬套传统 NAT 类型；页面用 NAT 类型 + 映射行为 + 过滤行为 + 可信度描述。
- 公共 STUN 服务器能力差异大，不支持 Changed/Other Address 时会显示基础 STUN 或增强检测不足，不强行误判。
- 固定签名配置继续保留；只要 GitHub Secrets 仍然是同一把 keystore，后续 versionCode 递增即可覆盖安装。
