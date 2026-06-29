# Labprobe v0.9.15 buildfix22

## MTU IPv6 探测修复
- IPv6 模式强制解析 AAAA / IPv6 地址，避免误用 IPv4 地址导致全失败。
- IPv6 MTU 改用 ICMPv6 Echo payload 估算，不再依赖 IPv4 的 `-M do` DF 参数。
- 检测前先做基础 Ping；基础 Ping 不通时明确提示目标不响应 ICMP / 网络受限，而不是把所有 payload 标成失败。
- IPv6 默认目标建议使用 `2400:3200::1`；IPv4 默认目标为 `223.5.5.5`。
- 更新 payload 说明：IPv4 `payload+28`，IPv6 `payload+48`。
