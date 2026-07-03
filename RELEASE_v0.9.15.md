# LabProbe v0.9.15 buildfix30

- 修复 NAS IPv6 与路由 WAN6 显示混用。
- NAS IPv6 只显示 Hub/NAS 本机检测结果；如果与路由 WAN6 完全相同会隐藏，避免误导。
- 路由 WAN6 支持 `wan6List`：单 WAN 显示“路由 WAN6”，多 WAN 显示“主用 WAN / 备用 WAN”。
- 保留 buildfix29 的 Hub/UI、release workflow 固定签名配置。



## buildfix31
- 修复 buildfix30 误隐藏 NAS IPv6，NAS IPv6 与路由 WAN6 完全分开显示。
- WireGuard 恢复使用 `[NAS IPv6]:51820`，不再因为 NAS IPv6 与路由 WAN6 相同而消失。
- versionCode = 77。


## buildfix32 - NAS IPv6 / 路由 WAN6 分离修复

- NAS IPv6 只显示 Hub/NAS 自己检测到的 `nas.exitIpv6`，不再和路由 WAN6 互相兜底。
- WireGuard 地址固定使用 `[NAS IPv6]:51820`，NAS IPv6 未获取时不使用路由 WAN6 顶替。
- 路由 WAN6 继续显示 `router.wanIpv6 / wan6List`。
