# P2 设置弹窗和独立提醒记录

- `HubNotifySettings.java` 是共用原生弹窗与记录页组件；主页面改动见 android.patch。
- backend.patch 基于 P1 部署后的实际源码，新增 helper 在相邻 notification_preferences.py。
- 配置按已有设备身份保存，类别局部更新；默认游戏/视频10分钟、社交30分钟、设备长时间120分钟。
- 支付仅选择已验证服务 ID，不提供时长规则。
- 原生记录页只显示 recordKind=reminder，按路由器和设备匹配；足迹继续仅展示 activity。
- 真实应用图标复用现有应用目录与缓存，分页创建应用选择行，避免一次创建完整目录。
- test_p2.py 的模拟数据仅用于规则边界与隔离测试，不代表真实系统通知送达。
