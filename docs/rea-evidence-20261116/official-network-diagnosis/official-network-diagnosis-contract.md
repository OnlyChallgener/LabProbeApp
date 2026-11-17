# 官方 APP「网络诊断」真实云端链路契约（2026-11-16）

## 目标与边界

只回答一个问题：星耀家原版 APP 的「网络诊断」卡片与「诊断详情」页，数据到底来自哪条云端链路、报文字段是什么。

* **静态基线**：`D:/Github/LabProbeApp/test/_h3b/shinya2-decoded/assets/index.android.bundle`
  `sha256 = fe8407a4f3f799c1867322ed546d6e8640f27b118d86eb5c99511a17446a930d`，9,553,672 bytes，Hermes v90，36,511 函数 / 51,936 字符串。FID 与字符串 ID 只对该指纹有效。
* **动态验证**：用户提供的官方账号，在 2026-11-16 22:30–22:50 (GMT+8) 直连 `shinyaapp.ruijie.com.cn` / `shinyaappws.ruijie.com.cn`。凭证不落盘、不写入本目录。
* 本轮**没有**重新扫描整个 APK，没有真机 UI 验收，没有拿到官方服务端实现。结论分「已证明 / 未取得 / 不推断」三段。

## 结论先行

`gameEnvCheck`（`startCheck` / `getLastCheckDetail`）是**游戏环境检测**，不是用户截图里的「网络诊断详情」。真正的链路是：

```
POST https://shinyaapp.ruijie.com.cn/api/v1/base/homeUser/accountPasswordLogin
     -> data.token (JWT), data.accoutId, data.groupId

WSS  wss://shinyaappws.ruijie.com.cn/ws/assistant?access_token=<JWT>
  1) init 帧           requestMessageType='init', messageDetail=''
  2) 触发帧            requestMessageType='emoji', messageDetail='diagnosticStandstill'
  <- 服务端推送 diagnose_loading 帧：uuid + 5 行 diagnoseType 明细
     （每一行带 status / normalNumber / exceptionNumber / problemDetailList）

POST https://shinyaappws.ruijie.com.cn/ws/assistantChat/getDiagnosisDetailResult
     {"uuid": "<上面的 uuid>"}
  -> data = {alias, overallScore, diagnosisTime, apModel, diagnosisProblemDetailMap}
```

详情页 `F33316` 用 `useQuery(['getDiagnosisDetailResult', uuid])` 取这份 `data` 作为 `diagnoseResult`；`F33246` 直接解析 `diagnosisProblemDetailMap` 各叶子的 `problemDetail`。

### 两个致命细节（实测坐实）

| 细节 | 错值 | 后果 |
|---|---|---|
| `projectId` 类型 | 字符串 `"18850348"` | 服务端回 `responseMessageType='noAp', diagnosisStatus='fail'`，误判无路由器 |
| 详情请求体 | `{"data":{"uuid":...}}` | `{"code":1,"msg":"no uuid"}`；必须顶层 `{"uuid":...}` |

另外登录返回字段官方拼写为 **`accoutId`**（少一个 n），不是 `accountId`。

## 静态证据（FID → 契约）

| FID | 作用 | 关键指令证据 |
|---|---|---|
| `F35752` | 构造 WS 报文 | `NewObject` 依次写入 `key/token/phoneNumber/account/projectId/type/requestMessageType/messageDetail/sender/uuid/diagnosisStatus`；`key = Date.now().toString()` |
| `F35744` | 连上后首包 | `sendMessage('', 'init', false)`（`Call4 1 4 0 3 2 1`） |
| `F35763` | UI 层 `sendMessage` | 对比 `startDiagnose` 快捷码；`chat`/`template` 走本地气泡 + WS `send(JSON.stringify(msg))` |
| `F35748` | 快捷按钮分发 | `diagnose_loading` 分支 `LoadConstString 'diagnosticStandstill'` → `F35766` |
| `F35711` | 快捷码映射 | 对象字面量 `{startDiagnose:'diagnosticStandstill', badsignal:'diagnosticBadsignal', ...}` |
| `F35768` | 诊断启动生成器 | `F35752(..., 'user', 'emoji')`；本地 `uuid.v4()` 造 5 行 `diagnosisType`（`loadStatus/interfereStatus/iotStatus/netStatus/signalStatus`） |
| `F35732` | WS 消息分发 | 按 `responseMessageType` 分派 `diagnose_loading` / `diagnose` / `speedTest` / `multiRequest` / `noAp` / `noNet`；读 `uuid`、`diagnosisStatus`、`messageDetail` |
| `F20856` | 详情 REST | `post(base + '/assistantChat/getDiagnosisDetailResult', {data: <params>})` |
| `F33316` / `F33317` | 详情页 hook | `useQuery(['getDiagnosisDetailResult', uuid])` → `getDiagnosisDetailResult({uuid})` → `{diagnoseResult: ...}` |
| `F33246` | 详情页渲染 | 读 `diagnosisProblemDetailMap`、`problemLevel`、`problemDetail` |
| `F35948` | 卡片行 | 读 `diagnosisType/status/normalNumber/exceptionNumber` |
| `F33242` | 结果页头 | 读 `apModel/alias/overallScore/diagnosisTime`，时间 `format('MM-DD HH:mm')` |

字符串表：`S7217='/assistantChat/getDiagnosisDetailResult'`、`S7210=getCommonQuestion`、`S7220=savePingLog`、`S1588=setDiagnosisPause`、`S25082='getDiagnosisDetailResult'`、`S31975='getDiagnoseResult'`、`S41396='startDiagnose'`、`S42177='startToDiagnose'`、`S47189='requestMessageType'`、`S35759='diagnose_loading'`、`S18354='multiRequest'`、`S3319='diagnosticStandstill'`、`S16019='emoji'`。

## WS 报文字段（12 个，缺一不可）

```json
{
  "uuid": "",                       // 新消息为空，服务端在响应里下发 uuid
  "key": "<Date.now() 字符串>",
  "type": "emoji",
  "token": "<登录 JWT>",
  "phoneNumber": "<账号手机号>",
  "account": "<accoutId，整数>",
  "projectId": 18850348,            // 必须整数
  "requestMessageType": "emoji",
  "messageDetail": "diagnosticStandstill",
  "sender": "user",
  "diagnosisStatus": ""
}
```

`init` 帧同形状，只改 `type`/`requestMessageType` 为 `init`、`messageDetail` 为 `''`。

服务端首帧对 `init` 回 `{"responseMessageType":"multiRequest", ...}`；对触发帧回 `diagnose_loading`，`messageDetail` 是 5 行数组的 **JSON 字符串**，`uuid` 在顶层。

## 动态证据（真实云端输出，已脱敏）

* 登录：`code 0`，`accoutId=3609212`，`groupId=14937366`，JWT 394 字符。
* 项目列表：`POST https://shinyaapp.ruijie.com.cn/homewlan/apMonitor/getProjectList2`（header `type: shinyaApp`）返回 `18850348 BE72-PRO(行者) onlineAp=1`。
* `init` → `multiRequest`；`diagnosticStandstill/emoji` → 9 帧 `diagnose_loading`，uuid `a54d6912aea643d596949cd88442a4a2`，五维依次完成，末帧 `diagnosisStatus='fail'`（干扰维度异常）。
* 五维最终计数：`loadStatus 5/0`、`interfereStatus 6/1`、`iotStatus 13/0`、`netStatus 2/0`、`signalStatus 13/0`。
* 真实 `problemDetailList` 里出现用户截图中的「5G终端长连2.4G信号」及 4 台终端名与时长：
  `iPad 1h31min 00:38-19:10`、`小米智能音箱(LX06) 12h34min 00:04-22:42`、`TCL电视 0h16min 19:30-19:51`、`iQOO Neo3 2h05min 20:40-22:45`。
* `getDiagnosisDetailResult` 返回：`alias='BE72'`、`overallScore=85`、`apModel='BE72-PRO'`、`diagnosisTime='2026-11-16 22:46:10'`，5 个 category 的 `diagnosisProblemDetailMap`：
  * `ap_reboot.problemDetail = {"last_time":"11:22","reboot_count":1}`，带 `problemSolution` 建议；
  * `24g_interfere.problemDetail = {"use5":"4.18","interfere":"小","use24":"23.66"}`；
  * `5g_connect_24g.problemDetail` = 上述 4 台终端数组；
  * 无异常的叶子（`link_instability`/`iot_shake`/`signal_weak`/`sta_ping`）字段为 `null`，这是官方本身的行为，不是 HUB 造的 `--`。
* 时间：首帧约 0.8–3s 出现 uuid；5 维全部完成约 18s。详情报告在 uuid 出现后即可读取，随时间逐步填充。

原始帧与详情样本（已去掉 token/账号）：
`D:/Github/ShinyaHub/test/diag-rollback-20261116/official-ws-diagnosis-frames.json`
`D:/Github/ShinyaHub/test/diag-rollback-20261116/official-diagnosis-detail-result.json`

## 与旧结论的关系

* 之前接的 `gameEnvCheck` 只有 `sta/wifi/ap/net/game` 五项**游戏环境检测**，因此 `ap_reboot`、`link_instability`、`iot_shake` 只能留 `--`，`24g_interfere.use24` 为 `None` 渲染成 `null%`，`5g_connect_24g` 只有 MAC 没有名称/时长——与用户截图完全对不上。这次换成助手诊断链路后，这些字段全部来自官方原样报告。
* MACC `webproxy` / 睿易 `enet/wio` 链路与星耀家网络诊断无关，已废弃。

## 已证明 / 未取得 / 不推断

**已证明**：bundle 中上述 FID 的指令与字符串字面量；WS 握手 101；12 字段报文被服务端接受并按 `requestMessageType` 分派；整数 `projectId` 才能触发诊断；顶层 `uuid` 才能读到详情；真实报告字段名与值的形状。

**未取得**：官方服务端实现与判定公式、真机 UI 截图对比、完整接口清单、`multiRequest` 的服务端语义、`getDiagnoseResult` 的实际 REST 路径（bundle 中只见方法名，未找到对应 path 字符串）。

**不推断**：不把 `gameEnvCheck` 的分数当官方网络诊断分；不把本地扫描/手机 ping/WAN 在线冒充官方指标；不把 `problemLevel=0` 或缺失字段写成绿色「正常」；不补零、不伪造终端名与时长；不声称完成所有接口或视觉验收。

## 重放

`capture-official-network-chain.py` 从环境变量读取账号与项目 ID（不落盘），可重放 WS + 详情读取；静态反汇编见 `official-ws-disassembly.txt` 与 `evidence.json`。

## HUB 生产落地与收尾（2026-11-16 续）

**状态缓存的真实路径**：`runtime.build_runtime()` 传给 `create_app()` 的
`official_account_directory` 是 `SHINYA_DATA_DIR/official-account`，不是
`SHINYA_CONFIG_DIR`。因此 `api.py` 里状态文件的实际位置是：

```
/vol1/@appdata/shinya-hub-fnos/data/official-account/official-network-diagnosis.json
```

首次预置放在 `config/` 时服务读不到，已迁到上述位置，属主
`shinya-hub-fnos:shinya-hub-fnos`、权限 `600`，服务账号写入验证通过。

**半填充报告保护**：后台每次点击都会刷新下一轮云端会话。若新会话在
5 行计数到齐前断开，或 `detail(uuid)` 还没填充完，旧实现会把
`rows=空 / result={}` 写进缓存，下一次点击就会退回空卡片。现改为：

* `_complete_report(rows, result)` 要求 5 行、`status` 均为 success/fail、
  计数总和 > 0，且 `diagnosisProblemDetailMap` 存在；
* 只有完整报告才替换缓存；不完整时保留上一次完整报告，仅记录
  `lastAttemptAt` / `startedAt`；
* `run()` 在 `detail(uuid)` 暂空且缓存自带完整 result 时直接返回缓存，
  不进入等待循环。

**生产端到端冒烟**（以服务账号、生产 `PYTHONPATH` 跑
`build_runtime()` + `XiaoxingService.diagnosis_start()`，脚本见
`D:/Github/ShinyaHub/test/diag-rollback-20261116/prod_network_diagnosis_smoke.py`）：

```
official factory wired: True      official project id: 18850348
start state: complete             source: official-cloud-network-diagnosis
overallScore 85  alias/model ('BE72','BE72-PRO')  diagnosisTime 2026-11-16 22:46:10
steps: 5/0 · 6/1 · 13/0 · 2/0 · 13/0（不再全部为 1）
ap_reboot     {'last_time':'11:22','reboot_count':1}
24g_interfere {'use5':'4.18','interfere':'小','use24':'23.66'}
5g terminals  iPad 1h31min 00:38-19:10 / 小米智能音箱(LX06) 12h34min 00:04-22:42 /
              TCL电视 0h16min 19:30-19:51 / iQOO Neo3 0h42min 07:42-10:49
detail source: official-cloud-network-diagnosis   healthz=200
```

终端名官方字段是 `staName`（另有 `duration` / `durationTime` /
`wifiBand`），HUB 原样透传，不再出现「只有 MAC 没有名字和时长」。

**回归**：诊断相关 84 passed / 43 subtests；全套 `pytest -q`
`939 passed, 5 skipped, 1213 subtests passed`，零失败（此前 5 个失败分别是
发布树快照过期与本机缺 `amqtt/pwdlib`，已重新 `stage_fnos.py` 并补装
`requirements.txt` 里已声明的 `amqtt==0.12.1`）。

**APP 侧**：本轮改动全在 HUB 数据契约与云适配器，未重新打包 APK。
改版 APP 使用的仍是官方 renderer（`F33246` 解析 `problemDetail`），
它通过 `messages[0].diagnoseResult` 读这些字段。真机 UI 验收仍需用户
清空聊天记录后点一次网络诊断确认；此处不声称已完成视觉验收。

## 2026-11-17：缓存报告冒充「本次结果」的排查与修复

用户在 07:46 的截图里看到 `19:10 / 22:42 / 19:51 / 07:42-10:49`
这些异常时间段，质疑「今天才 7 点多，10 点多和晚上的数据就出来了」。
核查结果：

* 生产缓存文件里 `uuid=a54d6912aea643d596949cd88442a4a2`、
  `result.diagnosisTime=2026-11-16 22:46:10`，即**昨晚生成的官方报告**。
  截图里的时间段是昨天全天的累计异常区间，不是今天现测，也不是编造。
* 用户点击时 `run()` 的 `startedAt` 被更新（文件 mtime 07:46），但
  `updatedAt` 仍是昨晚、`lastAttemptAt=None`：后台刷新会话没有产出新报告。
* 生产环境手工跑一次 `_session()` 得到
  `{"responseMessageType":"noAp","diagnosisStatus":"fail"}`，无 uuid。
  但官方项目列表 `18850348 BE72-PRO(行者) onlineAp=1`，主 AP
  `G1TD8RX039025 isOnline=true`，所以 `noAp` **不是设备离线**。
* 对照实验：同一账号连续三次连接，`noAp` / `diagnose_loading(running)` /
  `diagnose_loading(running)`。加 `sn` 字段的那次也成功，但不加 `sn`
  的对照同样成功，因此 `sn` 不是原因。结论是**云端间歇性回 `noAp`**，
  服务端判定逻辑未取得，不推断。另一个项目 `17418490` 回 `noNet`，
  是不同错误。
* 旧版后台 worker 把异常 `except: pass` 静默吞掉，失败后既不重试也不留痕，
  于是每次点击都 replay 昨晚那份报告，界面又没标诊断时间 → 看起来像假数据。

修复（`official_network_diagnosis.py` / `xiaoxing.py`）：

* 每次刷新最多 2 次会话，`noAp`/无 uuid 时重试；
  `getDiagnosisDetailResult` 报告填充期最多轮询 6 次。
* 失败写 `lastError` / `lastAttemptAt` 并打日志，不再静默吞异常；
  只有 `_complete_report()` 判定完整的新报告才替换缓存。
* 缓存报告超过 `STALE_AFTER=600s` 时，本次点击短等最多 `STALE_WAIT=12s`
  等新报告；等不到就明确返回 `stale=True` + 真实 `diagnosisTime` +
  `reportAgeSeconds`，不再声称「本次网络诊断结果」。
* 卡片 `message`、`detail`、`diagnoseResult` 均带
  `diagnosisTime` / `dataFreshness` / `stale` / `cloudRefreshError`。

修复后在生产同步触发一次真实云端会话，缓存被替换成**今天的新报告**：

```
uuid a659ffb167b948a693f64df8360db84f   complete True
diagnosisTime 2026-11-17 08:03:28     overallScore 85
rows 5/0 · 6/1 · 12/0 · 2/0 · 12/0   （iot/signal 由昨晚的 13 变为 12）
24g_interfere {use5:'1.57', use24:'24.05'}（昨晚 {use5:'4.18', use24:'23.66'}）
5g_connect_24g 由 4 台变为 2 台：iPad 0h20min 03:04-05:54、
小米智能音箱(LX06) 5h47min 00:00-08:02
ap_reboot.problemDetail 为 null —— 官方今天未报告重启，不是字段缺失
```

端到端冒烟：`client.stale=False`、`age=30s`、`cloudError=None`，
`start message = '官方云端网络诊断 · 38 项检查，异常 1 项 · 报告时间
2026-11-17 08:03:28'`，`healthz=200`。数据随时间真实变化，证明不是静态假数据。

## 2026-11-17 续：卡片结论缺失与「负荷负载 --」

用户 08:22 对比三张截图提出两点：改版 APP 主卡片标题是「诊断完成」，
官方原版是橙色的「当前网络不佳」；详情页「负荷负载」显示 `负荷 --` / `负载 --`。

**结论字段**：官方 WS 流末帧 `diagnosisStatus='fail'`（周围干扰维度 1 项不佳），
但 `_diagnosis_view()` 把对外的 `diagnosisStatus` 赋成了本地 job 完成状态
`status`（`state=complete` → `success`）。官方卡片按云端 verdict 选标题，
所以 HUB 永远显示「诊断完成」。修复：`network_primary` 时取
`official_report['diagnosisStatus']`（缺省按异常项推导 fail/success），
统一写入 `messages[0]`、`detail`、`diagnoseResult`、`publish_diagnosis`；
`state` 仍保持 `complete`，不把「有异常」误当成「任务失败」。
冒烟确认 `diagnosisStatus=fail` / `job state=complete` / `fresh=current`，
报告时间 `2026-11-17 08:28:35`。

**负荷负载空值**：查了三处官方原始数据 ——

| 来源 | `loadStatus` 实际内容 |
|---|---|
| 昨晚 `getDiagnosisDetailResult` | `high_ap_load.problemDetail=""`、`high_sta_load.problemDetail=""`、`problemLevel=0` |
| 今天 08:18 报告 | 同上，全为空字符串 |
| 官方 WS 9 帧 | `loadStatus.problemDetailList=[]`，末帧 `status=success, normalNumber=5, exceptionNumber=0` |

即官方云**在无异常时不上报 CPU / 内存 / 在线数**，只给「5 项检查均正常」的计数
（已显示在主卡片）。`--` 是官方数据本身的空值，不是 HUB 丢字段。按
「不补零、不伪造」原则不填数值，也不混用 `gameEnvCheck` 的数值冒充网络诊断。
若必须显示具体数值，需要另找官方数据源或明确标注为本地实时读数 —— 未做，不推断。

**部署**：`xiaoxing.py` sha256 一致，gunicorn master 已换为 `2191298`
（旧 pid 1767477 已不存在），HUP 后新 worker `2199594` 于 08:29:19 启动，
`healthz=200`。改版 APP 未重新打包，renderer 仍是官方 `F33246` / 卡片逻辑。

## 2026-11-17 续 2：负荷负载「没提供值 = 状况良好」

用户给出业务规则：官方负荷负载**没提供值就是状况良好**，不好的时候才按实际内容显示。
据此重新核对官方 renderer，而不是把 `--` 当成「官方本来就这样」：

* `docs/rea-evidence-20261024/diagnose-detail-renderer-disassembly.txt`
  的 **FID 33252** 里带有官方字符串 `S20944 = '状况良好'` / `S20943 = '状况不佳'`，
  按 `high_ap_load.problemLevel`、`high_sta_load.problemLevel` 生成 `showText`。
* 同文件另一个函数对 `detailData.problemDetail` 直接做 `JSON.parse(...)`
  （1212–1235 行）。官方云在无异常时给的是 `problemDetail=""`，
  `JSON.parse("")` 抛异常 → 组件走到 `--` 兜底。所以 `--` 是**空字符串让官方
  renderer 解析失败**，不是「官方设计上就显示 --」。
* `high_ap_load` 读 `cpuUse/memUse/cpuTemp`，`high_sta_load` 读
  `onlineCnt/bandWide`（见 `DIAGNOSIS_BADGES_AND_LOAD_20261101.md`）。

HUB 修复（`official_diagnosis_view.py: network_problem_map`）：

* 仅当叶子属于 `high_ap_load` / `high_sta_load`，且 `problemDetail` 为空
  （`""` / `None` / 不可解析 / 空对象）且 `problemLevel <= 1` 且 `problem` 为空时，
  判定为官方的「状况良好」：
  `problem = '状况良好'`，`problemDetail` 改成**可解析 JSON**，
  测量键全部为 `null`，另加 `available:false / healthAvailable:true /
  outcome:'状况良好'`。不编造 CPU、内存、温度、在线数、带宽。
* 官方一旦给了实际内容（真正的异常），`problemDetail` / `problem` /
  `problemLevel` **逐字透传**，不改写。
* 其它维度（网络连通 / 信号 / 干扰 / 智能家居）不受影响。

生产冒烟（映射后的 `messages[0].diagnoseResult`）：

```
mapped high_ap_load problem: 状况良好
mapped high_ap_load detail: {"cpuUse": null, "memUse": null, "cpuTemp": null,
                             "available": false, "healthAvailable": true,
                             "outcome": "状况良好"}
mapped high_sta_load problem: 状况良好
mapped high_sta_load detail: {"onlineCnt": null, "bandWide": null, ...}
start diagnosisStatus: fail      start state: complete
```

新增契约测试锁住：空值→良好且所有数值为 null、官方异常内容逐字透传、
其它维度不受影响。**未 patch APP renderer，未重新打包 APK**：
`JSON.parse` 不再失败，但「负荷 --」两行最终显示「状况良好」还是官方 renderer
对 null 测量值的兜底，仍需真机点一次确认；此处不声称已完成视觉验收。

## 2026-11-17 续 3：「小星助手接口暂不可用，请稍后重试」

用户 09:34 反馈点网络诊断出现该提示（先误说网络测速，随即更正为网络诊断），
并确认 09:32 的服务重启是**手动操作**。

**文案来源**：`tools/shinya_hub/HubStationModule.java:590-593`，是 APP 端
`session.request("assistantChat/send", body)` 抛异常且无快照可用时的兜底
`responseMessageType='chat'` 消息。不是 HUB 返回的错误文本。

**生产日志时间线**（`/vol1/@appdata/shinya-hub-fnos/hub.log`）：

```
09:32:24 official diagnosis refresh: cloud answered noAp/no uuid (attempt 1)
09:32:25 [2191298] INFO Handling signal: term        <- 用户手动重启
09:32:30 Worker exiting (pid 2204876)
09:32:33 Shutting down: Master
09:33:05 [2220259] Starting gunicorn 23.0.0
09:33:13 WARNING dispatch lookup failed /api/v1/assistantChat/diagnosis/status:
         LookupError: 未找到当前项目的助手诊断任务   (-> code 404)
09:33:28 WARNING dispatch rejected /api/v1/assistantChat/send:
         ValueError: 该诊断编号已使用，请创建新任务   (-> code 400)
```

APP 侧 `restoreAssistantTasks(history)`（Java 384-397 行）从本地聊天历史恢复
旧的诊断 uuid，服务重启后继续用它轮询/重试。HUB 的 `diagnosis_start()` 遇到
`diag_uuid in records` 直接抛 `ValueError` → `api.py` 把 `ValueError` 映射成
`code=400` → APP 把它当请求异常 → 显示「接口暂不可用」。即**可恢复的状态冲突
被当成硬错误**，而不是真的接口故障。

**修复**（`xiaoxing.py` 的 `diagnosis_start` 与 `_diagnosis_start_local`）：
重复 uuid 不再抛异常，改为 `diagnosis_status(key, project, uuid)` 幂等重放
已保存的官方报告；若记录损坏/查不到（LookupError/ValueError）则生成新 uuid
正常跑一轮。这样重启或丢响应后的重试都能拿到结果，且不会重复打云端。
`diagnosis/status` 对未知 uuid 仍返回 404，但那是 APP 的 poll 分支
（`pollSilently`），不会显示该文案。

生产冒烟：`replayed tap state: complete` / `uuid kept: True` /
`has result: True`；`start diagnosisStatus=fail`、`state=complete`、
负荷映射仍为「状况良好」、`healthz=200`。

## 2026-11-17 10:00：按官方链路重构成流式；负荷负载渲染链路闭环

用户一次提出三点：① 连续诊断第二次第三次都是缓存；② 官方五条按顺序
出结果，改版一直转圈后统一出；③ 负荷负载仍 `--`，要求查清真实链路。

### ①② 根因与流式改造

之前的架构确实偏离官方：`run()` 秒级返回缓存 + 后台刷新节流 24s；
`diagnosis_start()` 同步等云端后一次性返回 `state=complete`。所以连续点击
拿到同一份缓存，且等待期间只有一个转圈态。

**APP 轮询契约（决定不需要改 APK）**：`HubStationModule.java:415-441`
`pollAssistantTasks()` 调 `session.request("assistantChat/"+kind+"/status",
{projectId, uuid})`，用 `assistantRunning(state)`（queued/starting/running）
决定是否继续轮询，`messages[]` 按 uuid 更新同一张卡；`subscribeAssistantTasks`
只在 `state` 为 running 时订阅。因此 start 返回 `state=running` 就能驱动
官方那种逐维出结果。

改造：

* `official_network_diagnosis.stream(pid, on_frame, on_done)`：**每次点击启动
  一轮真实 WS 会话**，每个 `diagnose_loading` 帧回调 `on_frame(rows,status,uuid)`，
  完成后 `on_done(outcome)`。缓存只在云端拒绝会话时作兜底并明确标注时间，
  不再冒充本次结果。
* `xiaoxing.diagnosis_start()`：立即返回 `state=running` + 5 行全 running，
  后台线程把每帧 rows 写进该 uuid 的 diagnosis record；`diagnosis_status()`
  返回当前进度（N/5），完成后返回完整官方报告。
* `official_diagnosis_view.network_steps()`：running 行 `status='running'`、
  `stageState='running'`、`stepMainTitle='正在检测'`，不再是「已检测 0 项」。
* `publish_diagnosis` 的 marker 增加 `finished_step`，每完成一维更新同一张卡，
  不产生重复卡片；只对 `run()` 的旧适配器保留同步兼容路径。
* 契约测试：`LiveStreamDiagnosisTest` 锁住 start=running、逐维 0→5→complete、
  连续三次点击生成三个 uuid 且三次都打云端。

生产冒烟（真实云端）：

```
start state: running   start diagnosisStatus: running
start steps: 5 行全部 ('...', 'running')
progress transitions: [('running',0),('running',1),('running',2),
                       ('running',3),('running',4),('complete',5)]
progress poll count: 30   final state: complete
start message: 官方云端网络诊断 · 40 项检查，异常 1 项 · 报告时间 2026-11-17 10:19:53
start diagnosisStatus (card conclusion): fail
```

约 15 秒完成，与官方 WS 会话耗时一致；五条按顺序出结果。

### ③ 负荷负载真实链路（反汇编闭环）

`docs/rea-evidence-20261024/diagnose-detail-renderer-disassembly.txt`：

| FID | 证据 |
|---|---|
| `F33246` 521-600 | `problemLevel === 1` 时跳过 parse 用默认常量；否则 `JSON.parse(problemDetail \|\| '{reboot_count:1}')` 取 `reboot_count/flapCount/upLinkSpeed`。数值行读的是 **`problemDetail` 的键**，`problem` 字段不参与 |
| `F33252` 1077-1170 | 常量 `S20944='状况良好'` / `S20943='状况不佳'`；按 `high_ap_load.problemLevel` 生成 `showText=[{text,isProblem}]` 与 `isShowProblem`。`problemLevel=0` → text="状况良好"、`isShowProblem=false` |
| `F33247` 673-719 / 1195 起 | `isShowProblem=false` 时 `JSON.parse(detailData.problemDetail \|\| '{}')` → 数值行 childView |

官方原始数据（昨晚与今天报告 + WS 9 帧）：`high_ap_load.problemDetail=""`、
`high_sta_load.problemDetail=""`、`problemLevel=0`、`problemDetailList=[]`。

**更正（10:44 截图复核后）**：上面这段「官方 APP 也显示 --」的结论是**错的**。
真正的 `--` 来自我们自己打进 APK 的补丁
`tools/shinya_hub/patch_assistant_diagnosis_detail.py`：

* `guard()`（21-49 行）被注入到 CALLBACKS
  `(33247,33249,33251,33253,33255)` 的开头（87 行
  `rewrite(reader,fid,{0:bytes(c.raw)},{})`）。
* 它读 `detailData.problemLevel`，**falsy（0）就走自定义分支**，
  41 行 `c.string(47,'--')` → `showText=[{text:'--'}]`、
  `childView=null`、`isShowFold=false`，直接替代原版 typed renderer。
  truthy 时 `Ret null`，让原版 renderer 继续。
* 官方云负荷叶子是 `problemLevel=0`（含义是「已检查、无异常」），
  被这个 guard 当成「未知」→ 两行 `--`。而 `netStatus` 等叶子是
  `problemLevel=1`，走原版渲染，所以显示「0次」等正常值。
* 原版 `F33252` 在 `problemLevel===1` 时（1101 跳 0x68）生成
  `showText=[{text:'状况良好'}]`。因此只要让负荷叶子 level 为真，
  原版「状况良好」就会显示，**不需要重新打包 APK**。

**11:05 再次更正：上面「规范成 1」的修复是错的，已回滚。**
用户提供官方 APP 截图（11:04「状况良好」且**不能下拉**）与改版截图
（11:01「状况良好」但可下拉，展开出 `CPU温度 正常 / CPU利用率 正常 /
内存利用率 正常 / 设备同时在线 0台 / 带宽最高占用 0Mbps`），并指出：
官方没回这些数值，没回就不该有展开。

* `F33252` 1124-1136：`isShowFold = (problemLevel !== 0)`。官方
  `problemLevel=0` → 不可下拉；把 level 改成 1 会打开下拉，typed renderer
  用空的 `problemDetail` 画出**默认占位**（这些「正常 / 0台 / 0Mbps」不是
  官方云返回值，也不是 HUB 本地算法，见
  `patch_assistant_diagnosis_detail.py:88-108` 的 falsy-default 注释）。
* 官方云原始证据：`high_ap_load.problemDetail=""`、`problemLevel=0`；
  WS 帧 `problemDetailList=[]`，只有 `problem="负载状态-正常"`、
  `normalNumber=5`。HUB 映射后 `cpuUse/memUse/cpuTemp/onlineCnt/bandWide`
  全为 `null`，**未注入任何本地测量值**。
* 已回滚：`network_problem_map` 不再改写 `problemLevel`，保持官方原值 0；
  仅保留 `problem='状况良好'` 语义标注与可解析 JSON（数值全 null）。
  生产冒烟 `mapped high_ap_load level: 0`，流式进度仍
  `0→1→2→3→4→5→complete`，`healthz=200`。
* 回滚后改版 APP 因 guard 的 41 行写死 `--` 会显示两行 `--`，但
  `isShowFold=false`、`childView=null`，**不再出现假展开数据**。
  要同时得到「状况良好 + 不下拉」，唯一正确做法是 patch guard（仅对
  `high_ap_load`/`high_sta_load` 把 `'--'` 换成「状况良好」，保持
  `isShowFold=false`/`childView=null`）并重新打包 APK —— HUB 侧无解。

## 11:16：多次诊断后卡在「深度检测网络中」+「接口暂不可用」

用户截图：主卡五行全「已检测0项」一直转 + 两条
「小星助手接口暂不可用，请稍后重试。」

生产日志时间线：

```
11:14:14 - 11:14:35  连续 20 次 assistantChat/diagnosis/status，响应体
                      27360 → 35498 → 49137 字节（流式进度正常推进）
11:14:30              official diagnosis pid=18850348 streamed 11:14:16
11:15:35              POST assistantChat/send 200
11:15:35              [2230381] INFO Handling signal: hup  <- 部署回滚
11:15:36              Worker exiting (pid 2268155)
```

**根因**：gunicorn HUP 重启 worker，杀掉了正在流式跑官方 WS 会话的
daemon 线程，`on_done` 再也不会执行，诊断记录永久停在 `running`；
APP 的 `pollAssistantTasks` 因此无限轮询，卡片永远显示「深度检测网络中」。
HUP 窗口内被中断的请求则由 APP 兜底成「接口暂不可用」
（`HubStationModule.java:590-593`）。**是部署动作撞上用户正在测试，
不是云端故障。**

修复（`xiaoxing.py`）：新增 `DIAGNOSIS_TIMEOUT_MS = 60_000`；
`diagnosis_status` 对 official 且 running 的记录检查 `diagnosisTime`，
超时则 `_finish_diagnosis_error(...)` 持久化 `failed`，APP 停止轮询；
`_diagnosis_view` 的 `network_primary` 文案分支排除 `failed`/`cancelled`，
避免失败原因被覆盖成「官方云端正在诊断 · N/5 项已完成」。
新增契约测试锁住超时自愈与持久化。








