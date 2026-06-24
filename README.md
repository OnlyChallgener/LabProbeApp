# LabProbeApp v0.7.5

本版重点修复记录页事件字段、VPN 卡片展示、历史下拉菜单样式。

## 主要变化

- 记录页按新 `device_event` 字段显示
- 上线卡显示 IP / 信号 / 频段 / 速率 / SSID
- 离线卡显示在线时长 / 最后 IP / 最后信号
- 旧事件字段不足时只显示基础信息，不乱填
- 每日总结优先统计 device_event
- 下拉菜单改为圆角 Material 3 风格
- 历史记录下拉最多 3 条，可删除
- VPN 卡片删除说明小字
- WG 改为 WireGuard
- WireGuard / OpenVPN / EasyTier 按 Hub/Lucky Webhook 动态显示
- 没数据的 VPN 行和 DDNS 卡片隐藏
- 长地址保持单行横向滑动，点击复制


## v0.7.5
- Ping 参数框改为紧凑高度。
- Ping 延迟曲线增加 Y 轴数值刻度和 X 轴秒数刻度。
- 曲线区域改为 Material 3 渐变面板，减少灰白底。
- 首页排序行、刷新下拉框缩小，减少顶部留白。
