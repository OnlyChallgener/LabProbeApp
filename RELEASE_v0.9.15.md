# 极客网探 v0.9.15 build79 · Posix Ping 引擎自测版

## Ping 引擎重构
- ICMP 优先使用 `android.system.Os.socket(AF_INET/AF_INET6, SOCK_DGRAM, IPPROTO_ICMP/IPPROTO_ICMPV6)` 无特权 Socket。
- 使用 `Os.poll()` 单协程事件驱动收包，失败自动降级到系统 `ping` 单进程采样。
- 高频发包使用 pacing 节奏控制，降低 Runtime.exec 和线程调度带来的假性抖动。
- 增加 Jacobson/Karels 动态 RTO：根据 SRTT/RTTVAR 自动调整超时阈值。
- 抖动继续使用最近 50 个成功 RTT FIFO，相邻 RTT 绝对差平均；丢包/超时不进入抖动队列。

## Ping 图表优化
- Y 轴固定覆盖层最后绘制，曲线不会越过 Y 轴数字。
- Y 轴数字更靠左，X/Y 轴字号再收小，图形区域更大。
- 图表圆角缩小，曲线和 X/Y 轴更贴近，但保留边框安全距离。
- 继续使用现有可横向滑动波形图。

## 继承修复
- NAS IPv6 与路由 WAN6 继续分离。
- WireGuard 地址继续使用 NAS IPv6。
- Hub/路由脚本逻辑不变。

## 构建信息
- versionName = 0.9.15
- versionCode = 79
