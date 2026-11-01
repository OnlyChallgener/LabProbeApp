# P3 真实规则与进程内接收

本阶段仅保留相对 P2 实际源码的精确补丁，避免提交工作区混杂功能。

- android.patch：接收器接线、持久游标/送达账本/点击目标、旧服务停用及通知记录定位。
- backend.patch：有界长轮询、授权范围复核、设备身份解析及新规则接线。
- ../HubNotificationReceiver.java：APP 存活期间唯一接收实例；启动先补同步再发一条汇总；后续事件普通通知。
- ../notification_trigger_rules.py：真实活动区间、设备开始/结束/深夜/长时间、分类阈值与稳定去重。
- ../notification_wait.py：15秒有界等待，最多占用3个HTTP线程，保留普通页面处理能力。
- ../verify_notification_delivery.py：执行实际Java接收控制流程与SQLite语句，不能替代真实Android通知测试。
- test_p3.py：在实际线上包隔离副本中执行26项测试；不写路由器配置。

账号/HUB变化立即取消旧接收。没有前台服务、启动广播、厂商推送、连接状态通知。进程结束后由HUB保存，下次打开再接收，不承诺系统强停期间送达。

部署清单与回退见 deployment.json，线上源码哈希核验见 live-verification.json。新规则首次启用/迁移建立新观察基线，不追发历史活动。

P1/P2/P3的软件检查已分别执行；真机布局、系统通知显示、点击定位及后台重开仍须用户核对。ADB断开时不把自动测试称为真机通过。

交付核查补丁包含：分类弹窗保存超时重读、支付足迹可信缓存范围、页外内存首帧、路由器条目隔离、原子游标、接收实例释放、无时间戳旧配置的稳定基线。最新包见build-result.json（hub.38），hub35文件仅留作上次构建证据。后台规则测试现为27项；追加helper部署见deployment-hardening.json。native-source-hashes.json和最终DEX方法检查证明交付包包含当前修复；不代表真机UI/系统通知已验收。
