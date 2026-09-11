# 关注设备在网统计实现日志（2026-09-11）

## 范围

- 仅对 APP 本地明确关注的设备展示在网统计。
- 复用现有设备上下线事件、设备在线状态和本地事件缓存。
- 不修改 Hub、LabRelay、WSS、RPC、WG、STUN、端口映射或刷新频率。
- 不改变设备列表、详情页既有操作路径、设备图标和设备识别逻辑。

## 数据口径

- 设备以规范化 MAC 地址匹配，仅接受 `device_online` 与 `device_offline` 事件。
- 完整上线/下线边界组成一个会话；连续重复状态沿用现有事件归一化逻辑去重，真实的再次上线会重置下线去重窗口。
- 开放会话只有在事件末尾在线且当前设备快照也在线时才累计到当前时间。
- 跨午夜会话按本地日期拆分计入每日与小时分布，但记录列表仍显示为一个完整会话。
- 缺失、非法日期、尾随脏数据或无法配对的边界不推断在线时长，也不使用流量反推在线状态。
- 展示今天、近 7 天、近 10 天与今天 24 小时分布；保留周期沿用现有最近 15 天事件缓存。

## UI

- 关注设备的详情页在“连接概览”后增加一张紧凑实色统计卡。
- 摘要只显示连续在线、今日在线、今日上线次数。
- 图表支持今天、近 7 天、近 10 天切换；小时/日期标签对齐对应桶并显示当前尺度，使用现有蓝色与中性色，不使用紫色、粉色、渐变或大面积状态色。
- “查看记录”打开现有 Bottom Sheet，按日期分组并将一次上线/下线合并为一行。
- 图表保持实色；Sheet 使用项目现有材质层，内部不再嵌套玻璃。
- 全部文字复用 `LabTypography`，没有新增页面字号或修改全局 Typography、Spacing、Radius Token。

## 通知联动

- 设备上下线系统通知只发送给明确关注的设备；非设备事件保持原规则。
- 单个关注设备事件携带规范化 MAC 并打开对应设备详情。
- 多个设备事件打开设备页；混合事件和非设备事件打开记录页。
- APP 每次进入前台后，在下一次成功同步时仅提醒最近一条关注设备上下线事件；同一 Hub、同一事件在 1 小时内不重复提醒。
- 1 小时内出现更新的关注设备事件仍会及时提醒；实时增量通知与 APP 打开提醒共享小时去重记录，避免连续弹出两次。
- `device_detail` 纳入 Hub 身份校验；冷启动与热启动继续使用唯一通知 URI 防止同一 Intent 重复消费。

## 验收标准

- PASS：非关注设备详情不出现统计卡，也不产生上下线通知。
- PASS：完整会话、跨午夜拆分、当前连续在线、7/10 日窗口与 24 小时分布计算一致。
- PASS：漏掉下线事件但设备已离线时，不继续虚增在线时长。
- PASS：快速下线、再次上线、再次下线被识别为两个独立会话；非法时间不参与统计。
- PASS：统计页无大字号、过宽行距、嵌套卡片、方形阴影或紫粉色元素。
- PASS：单条关注设备通知直达正确设备；批量与其他事件仍进入原有总览。
- PASS：重复打开 APP 只提醒最新一条，且同一事件 1 小时内不重复；一小时后允许再次提醒。
- FAIL：使用流量推断在线、为缺失数据补零、未关注设备出现统计，或改变现有设备操作路径。

## 验证边界

- 本机只运行 Python 静态契约测试与 Git 差异检查，不安装或运行 Android SDK、Gradle、模拟器。
- Kotlin/JUnit、Robolectric、Compose 与 Release 编译交由 GitHub Actions 验证。

## 验证日志

- 2026-09-11：完成关注设备在网统计（FollowedDevicePresence）、卡片与记录 Sheet（FollowedDevicePresenceUi）、设备详情集成（DeviceDetailV2）实现。
- 2026-09-11：完成事件规范化（DeviceEvents）再次上线重置下线去重窗口与严格时间解析。
- 2026-09-11：完成关注设备通知过滤（filterEventNotifications）、单关注设备直达详情（eventNotificationTarget / MainActivity）、前台打开成功同步 1 小时限频提醒与实时通知共享去重（selectLatestFollowedPresenceReminder / claimLatestFollowedPresenceReminder / notifyLatestFollowedPresenceOnOpen）。
- 2026-09-11：审查生命周期与并发细节：`setForeground` 设置 `startupPresenceReminderPending` 标记；成功同步时单次消费标记；SharedPreferences 原子提交；`CancellationException` 保持重抛；异常不阻断数据流。
- 2026-09-11：本地 Python 静态契约测试与回归（44 项用例全部通过，包括 `test_followed_device_presence_contracts.py`、`test_notification_contracts.py`、`test_wg_stun_contracts.py`、`test_wg_stun_recovery_contracts.py`）；Git diff 检查无空白或格式异常。
- 2026-09-11：GitHub CI `34575806321` 通过：Python 静态契约测试、Kotlin/Android 单元测试与 Release 编译全部通过。
- 2026-09-11：GitHub Test Bundle `34576541390` 通过并发布测试版 Prerelease：`test-bundle/followed-device-presence-20260911`；包含签名测试 APK `LabProbe-v0.12.0-build241-test.apk`。
