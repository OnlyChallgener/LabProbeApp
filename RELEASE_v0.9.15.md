# Labprobe v0.9.15 buildfix22

## MTU IPv6 探测修复
- IPv6 模式强制解析 AAAA / IPv6 地址，避免误用 IPv4 地址导致全失败。
- IPv6 MTU 改用 ICMPv6 Echo payload 估算，不再依赖 IPv4 的 `-M do` DF 参数。
- 检测前先做基础 Ping；基础 Ping 不通时明确提示目标不响应 ICMP / 网络受限，而不是把所有 payload 标成失败。
- IPv6 默认目标建议使用 `2400:3200::1`；IPv4 默认目标为 `223.5.5.5`。
- 更新 payload 说明：IPv4 `payload+28`，IPv6 `payload+48`。


## buildfix23
- IPv4 公网出口测试增加多个备用源，避免单一 api.ipify.org 失败就误判。
- 双栈测试按真实返回 IP 判断 IPv4/IPv6。
- 运营商查询改为在线 ASN/Geo 查询，失败才标记失败。
- DNS 项改为“本机 DNS AAAA 解析能力”，避免误导为严格运营商 DNS IPv6 权威接入测试。


## buildfix24 · 设备事件去重热修

- 终端上线/离线事件按 MAC 优先、名称/IP 兜底做状态机去重。
- 同一设备已经离线时，后续刷新仍为离线不再重复显示离线记录。
- 离线在线时长优先按 offlineAt - onlineSince 固化计算，避免早记录比晚记录显示更长/更短的错乱。
- 同一设备连续离线增加 5 分钟冷却保护。
- 版本仍为 v0.9.15，versionCode = 69。


### buildfix25
- 调整漫游/测速图表 Y 轴：字号缩小一号、位置左移但保留圆角安全距。
- DNS 解析结果加入联网 ASN/Geo 查询，失败时回退本地/IPv6 前缀判断。
- versionCode 70。
