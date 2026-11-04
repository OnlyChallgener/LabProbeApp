# 通知记录 V2：P1 测试交付 / hub.46

本轮以现有工作区和 hub.44 为基线。未修改 HUB 协议、提醒触发规则、儿童上网统计。ADB 不可用，以下不构成手机 UI 验收；P2/P3 尚未进入验收阶段。

## 修改

- 通知记录标题、返回、刷新共用固定高度居中标题栏；六分类可横滑。
- 最近三天每日概览：真实提醒次数、主要应用、高峰小时、涉及应用数；24 个小时桶绘制紫色通知次数柱。没有可靠会话关联时不显示推算时长。
- 每日明细一个白色圆角容器；只有浅横线，没有竖线。今天展开、过去日期折叠，刷新保留折叠状态和已有视图。
- 四类提醒及四种场景图标使用统一线宽的原生几何路径绘制。设备用网保留用户提供的网络图标。
- 足迹分钟紧跟活动时间；支付/购物不再跳过已有 sessionRanges 中真实 activeSeconds/activeMinutes。无可信时长时不以时间跨度代替时长。
- IPv6：首帧同步读取按 HUB/账号/项目/路由器/MAC 隔离的持久化地址；历史快照使用 endTime=-1 明确区别于实时有效期。刷新仍独立执行；真实空结果可更新为空，失败不伪造地址。
- 新建网络：先以创建响应中的精确项目 ID 更新选择并清空旧路由器/终端，再刷新家庭列表、返回首页；移除原有 600ms 选择延迟。修正该新增属性读取的 Hermes 缓存槽冲突。

## 字段来源

通知仅接受现有 matches 过滤后的 reminder 记录。稳定主键 eventId/eventKey；修订选择 revisionSeq/updatedAt；发生时间 triggeredAt，旧数据回退 createTime；类别 category；应用 appId/appName。日期使用 Asia/Shanghai 当天及前两天。

上网足迹沿用儿童上网 children.report.sessionRanges 派生的 apps[].sessionRanges；时长只读取 activeSeconds 或 activeMinutes。付款服务访问不表示完成付款。

## 已执行检查

- compile_check.py：28 个原生源文件编译通过。
- verify_records.py：执行从生产源提取的 Java 聚合和时长代码，验证稳定事件去重、修订、分类、北京时间三天边界、未来记录排除、可信时长及缺失值不编造。
- verify_home_selection.py：执行最终 bundle 的实际 Hermes 创建回调，验证先选择指定新项目、清除旧设备，再刷新列表；验证帧容量及属性读取缓存槽。
- verify_terminal_ipv6_patch.py --bundle：最终 bundle 原始 IPv4 行保留、历史首帧、设备隔离、实时更新、到期、清理和复制路径通过。
- build_internal.py：ARM64 构建、签名、打包校验及现有助手运行边界检查通过。

## 测试包

`D:/Github/LabProbeApp/test/_h3b/1793781685/shinya-hub-ui-dev-arm64-v8a.apk`

版本 1.2.5-hub.46-dev / 183249957。

SHA256 `b10bee70cd7861c98596e8c7cdf2f9270fd8e008a478b11a12a18c052e2778b4`。

尚待手机核对：通知页实际截图；360/393/412dp、放大字体、返回重进；京东分钟；IPv6 冷启动首帧及新建空项目导航。不以构建成功宣称无闪退。

## 提交边界

工作区原有大量未提交改动。本阶段只暂存 Records、图标、IPv6 及家庭选择相关代码；HubPages 的完整文件含此前功能，故本轮最小足迹改动单独保存为 HubPages-footprint.patch，未把整个脏文件混入提交。APK 已包含此补丁。before-p1 文本和草稿 fragment 不是交付源。
