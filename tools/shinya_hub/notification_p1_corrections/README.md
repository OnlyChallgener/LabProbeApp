# P1 定向修正（基于 hub.39）

只修正现有通知链路，不重新开发通知系统。

- `HubNotifySettings.AppSelection` 统一全选和复选框；取消草稿不修改已保存值，全选保存为空集合加 `allApps/allServices=true`，保留新增目录应用也被关注的原语义。
- `notification_trigger_rules.py` 为设备会话加入稳定会话标识、真实区间累计秒数和结束摘要。旧提醒原位补充时段，保留 eventKey/createTime，修订不作为新提醒。不同起止区间不按相同标题合并。
- `notification_activity.py` 取消“最近两分钟即进行中”的推断，只有来源明确报告 ongoing 才显示进行中；已确认结束的同一事件不能被不完整刷新重新打开。
- HubPages 额外尊重可信结束字段；分类弹窗保存后与主开关走相同接收配置同步；图标部分获取失败时保留已缓存图标，不整批清空。
- `android.patch` 是相对本轮实际工作源码的精确增量；HubPages 存在历史混合修改，未将整文件纳入本次提交。

验证：32 项线上包隔离副本测试通过；实际 Java 全选/自定义/分页/保存回填/取消、结束标记保护、缓存/保存回读、接收器和 SQLite 送达去重测试通过；28 原生源编译通过。真实只读核验：iQOO Neo3 的 7 条结束提醒起止时段不同，均已原位补充时段，未删除或误合并会话。

部署仅包含 notification_activity.py 和 notification_trigger_rules.py；备份见 deployment.json。未写路由器配置，未修改儿童上网原始统计，也未制造提醒事件。构建与设备验收以随后追加的真实证据为准。

## 构建和安装

- 提交 `685625e`。
- hub.40 / `183249951`：`D:/Github/LabProbeApp/test/_h3b/1793544331/shinya-hub-ui-dev-arm64-v8a.apk`。
- SHA256：`51538bc44bb968e68f469bb6c94024346106e58c9508ff460b5617e24b29916b`。
- 原生 28 源编译、React 26 方法签名、Hermes 构建门禁、16KB 对齐、v1/v2/v3 签名通过。原生增量覆盖逐字节一致，见 native-overlay-check.json。
- 版本元数据校验拦下了缓存资源中的旧版本号；已重新构建资源，最终 aapt2 与安装后的 dumpsys 均确认 hub.40 / 183249951。中间产物未安装、未交付。
- ADB 覆盖安装返回 Success，数据保留。应用宝关闭 APP 后仅剩 Android 启动器，已请求用户从应用宝重新打开；不使用桌面控制或另行强制启动绕过宿主限制。新包实际交互尚需继续验证，不能用前版 hub.39 的十次进出结果冒充本包验收。

线上只读核查 `live-audit.json`：7 条结束记录对应 7 个不同稳定区间标识；全部保留起止时间、原事件键和创建时间，修订均为 `newReminder=false`。来源没有设备级会话 ID，标识明确基于 observedIntervals，不声称是固件提供的会话 ID。
