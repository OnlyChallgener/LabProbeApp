# LabProbe v0.9.15 buildfix30

- 修复 NAS IPv6 与路由 WAN6 显示混用。
- NAS IPv6 只显示 Hub/NAS 本机检测结果；如果与路由 WAN6 完全相同会隐藏，避免误导。
- 路由 WAN6 支持 `wan6List`：单 WAN 显示“路由 WAN6”，多 WAN 显示“主用 WAN / 备用 WAN”。
- 保留 buildfix29 的 Hub/UI、release workflow 固定签名配置。

