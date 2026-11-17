# APP 固件升级复核与设备隔离

日期：2026-11-13。仅检查和修改 APP，未执行固件下载、刷写或路由器重启。

## 已有实现，不能重复重做

- `patch_bundle.py` 保留原 RouterUpgrade、UpgradeManager、逐设备/逐通道过滤；仅红点判定兼容两个已确认升级通道。`verify_upgrade_preserved.py` 对照原始 HBC 检查原升级函数未改。
- HUB 已有 `hub/firmware/upgrade` 的 start/state；原版页面还有 `device/command/sendCmd` 和 `getCmdResult` 适配。设备端 `shinya_upgrade.lua` 已有下载/刷写后台任务。
- 因而“279/380 检测到新版”不是空壳检测，但不等于两个版本已实际刷写验收。不能按版本数字、型号或同名项目推断目标或兼容性。

## 本轮 APP 修复

文件：`tools/shinya_hub/HubToolsView.java`。

1. 测试版升级按钮在渲染时固定登录会话、项目 ID、SN 和页面代次；确认前、执行前、进度请求前及回调显示前均核验。
2. start/state 均从捕获的项目/SN 构造请求，发送 sn/apSn；不在后台读取可变的当前设备字段。账号/HUB 更换由 requireCurrent 阻断，设备/项目/页面切换由代次和目标比较阻断。
3. 采用串行写队列及重复提交保护。状态查询失败只重试查询，不重发升级命令；轮询有上限，旧页面回调不得显示到另一台设备。
4. 检查设备 error_code 和 ACK state。没有明确接收状态不显示下载成功。idle 仅提示核对版本，不直接显示“升级完成”。确认提示不再承诺未经实测的固定两分钟。
5. 当前 official 分支继续让 HUB 使用所选设备自己上报的固件包，不从别的设备或缓存页面拿 URL。

## 验证

- Java 30 个生产源码编译通过。
- `verify_upgrade_scope.py` 执行实际生产 helper：26 项，含项目/SN/会话/页面切换、空目标、设备拒绝、无效 ACK；另检查串行提交及有界轮询调用点。
- HUB 原有 `test_firmware_upgrade.py` 6 项通过，含外部 SN、只读账号、非法地址和官方包提取。此处没有改 HUB 源码，也没有部署。
- 上述测试没有发真实升级命令，不可写为 279/380 真机升级成功。尚需最终 APK 页面验收。

## HUB 交接：仍需补强，不在此对话抢改

已核对本地源代码：

- `hub/firmware/upgrade` state 使用 `UFCommand(..., None)`；设备 Lua module_get 会拒绝非 JSON 请求，需与已验证的 `{action: state}` 统一，并验证状态缓存键一致。
- start 分支未统一检查内层设备 error_code；APP 本轮已检查，但 HUB 仍应返回明确失败，不能仅外层 code=0。
- official 包选择应核对请求期望版本与当前设备重新检测版本，列表变化时返回重检，不能悄悄刷另一个版本。
- 原命令通道 `command_result` 在查 `_JOBS` 归属之前就读取设备状态，未知 cmdId/别的项目 job 不应投影成当前任务完成。应先验证账号/项目/SN/job 归属，再读状态。
- `firmware_command` 将 idle 映射 completed 不能证明更新完成；应结合任务存在、阶段及重连后版本。重启造成失联应保持等待/未知，不能自动报成功。
- Lua 下载应验证 HTTP/下载退出状态与固件有效性、sysupgrade 返回失败，不能只因文件非空就刷写或把失败返回写成 rebooting。此项属于已有实现审查发现，不代表已经验证了可利用问题。

两端最终验收需分别覆盖：同账号不同项目两台设备、同型号不同 SN、不同账号/HUB、弹窗期间切换、过期 ACK、重复提交、升级拒绝、重启失联恢复。真实刷写必须另行由用户选择设备与升级版本后执行。

## 本轮候选包

- 1.2.5-hub.56-dev / 183249967，arm64-v8a。
- `test/_h3b/1794574152/shinya-hub-ui-dev-arm64-v8a.apk`，89,319,696 字节。
- SHA256：`d9cc3e32551e92d581d8275a6c01d024860bd734ff6d5ce96365e8273fcf8d97`。
- 18 组构建检查、13 项编译后项目/主设备合同、原升级页保持检查通过；签名 v1/v2/v3、16 KB 对齐通过。
- 应用宝当前调试连接关闭，未安装本包、未做本包 UI 实测。新建家庭回弹仍未取得本包实机状态链证据，不标记修复完成。

### 安装后追加验证

用户打开应用宝后已成功安装 hub.56（adb install -r 返回 Success）。用该 APK 自带 RN 运行真实 createConstantsForViewManager 检查通过：NativeProps projectId/sn/type、topChange/onChange 与默认事件合并正常。证据：`test/_h3b/coverage-runtime-check/accept-result.json`。调试启动未保持应用进程，已请用户手动打开 HUB；页面导航、新建回弹和升级页仍待实测。此更新覆盖上文“未安装”，不覆盖“未 UI 验收”。

### 多窗口验收限制

用户手动打开后，首页可见，点击工具页能渲染，未出现原来的通用错误页。但此时存在调试启动的 display 0 小窗口与应用宝 display 2 正常窗口两个 MainActivity；工具卡片出现严重宽度挤压，因此不能将这张图作为单窗口正常 UI 通过。截图 `test/_h3b/1794574152/ui-check/tools.png`。标准 uiautomator dump 对副显示器坐标截断，底部导航 bounds=0，自动导航脚本停止（不是崩溃证明）。移除重复调试任务导致应用宝关闭了两个窗口，已请求用户重新打开；后续禁止 am start 默认显示器或清理任务，以免干扰单窗口验收。

### 单窗口页面观察结果

重新手动打开后，PID 1983：工具页正常，预计覆盖概览/详情/返回均为120㎡、2房2厅、3堵；我的 MACC 图标可见且文字对齐；升级页显示当前 ReyeOS 1.279.2015，返回“无需升级”，未点击任何刷机动作。证据见 `test/_h3b/1794574152/ui-check/manual-ui-result.json` 及同目录截图。不能将本次“无需升级”扩大为279/380升级下发与完成验收。

补充真实崩溃证据：21:17:25 移除调试重复任务导致 PID1463 onHostPause 的 ReactInstanceManager 断言异常；这次是测试多窗口引入，不能归为已修复的工具组件异常，也不能声称该时段没有崩溃。后续单窗口 PID1983 导航正常，未重新清任务。

APP 脱敏真实列表日志：21:20:05，HTTP200、code0、count2；当前只有BE72/BE50。已请用户在hub.56再新建一次，等待ACK与后续列表同会话证据。


---

## 2026-11-17 追加：自研刷写已摘除，改走厂商双通道

本轮按 `docs/FIRMWARE_UPGRADE_DUAL_CHANNEL_2026-11-17.md` 实施（§8 逐行核查 +
BE72 真机只读取证修订了方案）：

* **结论**：设备侧 `shinya_upgrade.lua` 在本仓库从未进入部署清单（HUB + APP
  两份孤儿源码已删除），真机 BE72 上也确实不存在。此前 `hub/firmware/upgrade`
  的 `official` 分支从 shell 文本抠 URL 再交给 `shinya_upgrade` 是一条死链路，
  不是「97% 失败」。文档 §1 的 97% 归因仍属代码推断，未取真机失败现场。
* **现链路**：通道由 `ehr_beta_upgrade {action:'version'}` 有无载荷判定（绝不看
  云的 `code`；真机「无新版」是 `code:200` + `firmwareList:{}`）。Beta 走
  `set upgrade{keepConfig:true}` / `get progress` / `set cancel`；官方走
  `set shinya_exec upgrade` / `get shinya_exec upgradeState`。不再下发 shell 文本、
  不再有自定义 URL 兜底。
* **终态**：`113`/`upgrading` 即停轮询，文案「升级已启动，设备正在写入并重启」，
  不报成功也不报失败；重连后 `force=1` 强制重检。
* **真机只读实证**（未下发、未刷写）：`version` → 无新版；`progress` → `idle`；
  `shinya_exec upgradeState` → `{"state":{"upgradeState":"100"}}`；
  `uci show upgrade` → `upgradeState='100'`。当前无可用包，因此**未做真机下发验收**。
* **能力损失（已确认接受）**：任意 URL 刷写能力消失；有 Beta 模块的设备在 APP 内
  无官方正式版入口。

后续不要再按旧审计里「设备端 shinya_upgrade.lua 已有」的描述重做该链路。
