# 固件升级双通道（Beta / 官方）设计与实施报告

日期：2026-11-17。本轮只取证与讨论：**未改代码、未打包、未下发任何升级、未刷写设备**。
固件取证目录：`test/_be72_beta_20261114/fwdiff/tree/{v2,old,new}`（下文 `tree/new/...` 均指此）。
三棵树中 `ehr_beta_upgrade.lua` md5 一致（`796286666ac58fb53ab204d2e1836701`，11701 字节），`/usr/sbin/ehr_beta_upgrade` 亦一致（`be9940dc2e8153142cbfc798e4b41b63`），因此下述机制不是 Beta 包新引入的。

---

## 1. 开发背景：升级总是到 97% 失败

### 1.1 现象

- 用户报告：在 APP 里升级，进度总是走到 **97% 后失败**；无法判断是"包没下下来"还是"下完安装失败、包被删了"。
- 对照：**同一台设备在 eweb（路由器网页）Beta 在线升级页点"将保留配置升级"能成功**。
- 用户后续观察：官方 APP 升级也是一直显示"升级中"，没见到"升级完成"。

### 1.2 我们现有实现（问题所在）

APP → HUB → 设备自研模块，**完全不走厂商链路**：

- APP 固定 `channel:"official"` 提交（[HubToolsView.java:276](../tools/shinya_hub/HubToolsView.java#L276)）。
- HUB `hub/firmware/upgrade` 只把 `ehr_beta_upgrade` 当"版本检测"用（`api.py:808/842`，均 `{action:'version'}`），随后把 URL 交给自研模块（`api.py:856-857`）。
- 设备侧 `router_sync/shinya_upgrade.lua` 现场生成 `/tmp/shinya_upgrade/run.sh`：

| 环节 | 我们的实现 | 厂商 Beta wrapper |
|---|---|---|
| 下载超时 | `curl -L **-m 180** -k --retry 3`（3 分钟） | `curl -L -k **--connect-timeout 10 -m 900** --retry 3 --retry-delay 3`（15 分钟） |
| 断点续传 | 无 `-C -` | 无 `-C -`（official 通道有） |
| 下载前清内存 | 无 | `sync; echo 3 > /proc/sys/vm/drop_caches` + `rm -f /tmp/*.bin /tmp/*.tar.gz` |
| curl 退出码 | **不看** | `wait $CURL_PID; CURL_RET=$?`，非 0 即失败 |
| 文件判据 | `[ ! -s /tmp/firmware.tar.gz ]`——**只要非空就刷** | 退出码 + `md5sum` 双重校验 |
| 失败后处理 | 半截包留在 `/tmp`，继续 `sysupgrade` | state 108/110，**并 `rm -f` 掉包** |
| 进度 | 无百分比，只有 downloading/flashing/rebooting/failed | `floor(字节/totalSize*100)`，封顶 99，另出速率 |

证据：`shinya_upgrade.lua:67-78`；wrapper 见 `tree/new/usr/local/lua/dev_sta/ehr_beta_upgrade.lua:114-214`。

### 1.3 97% / 78% 这个数字从哪来（2026-11-18 真机已证）

- **我们的 APP 不产出百分比**：`watchUpgrade` 只把 HUB 的 `message` 文字塞进 status（[HubToolsView.java:313-331](../tools/shinya_hub/HubToolsView.java#L313-L331)），HUB 侧也只有四个文字状态（`firmware_command.py:78-80`）。用户截图的百分比页面是**官方 APP**，不是我们的 APP。
- **官方 APP 的百分比与设备真实下载无关**。2026-11-18 12:00 官方 APP 显示 78% 时，设备同一时刻：无固件包（`find /tmp /overlay /root /var -size +512k` 无命中）、无 curl/wget/sysupgrade 进程、无到固件服务器的 ESTABLISHED TLS 连接、`ehr_beta_upgrade_status` 与 `upgrade.recommend.upgradeState` 均为空闲、mqlink/ctcapd 日志无 upgrade 命令。37.7MiB 下到 78% 必须同时具备"增长的包 + 下载进程 + 持续 TLS 连接"，三者全缺。详见 `test/_upgrade_live_20261117/LIVE_EVIDENCE_2026-11-18.md` §7。
- 厂商自己的百分比只存在于 `ehr_beta_upgrade {action:'progress'}`（字节/totalSize，封顶 99）与 eweb 页面阈值（`upgrading || >=95` 停轮询）。官方 APP 走的是云推送链路，其进度条是客户端/云端计时动画。
- 因此"停在 97%/78% 后失败"= **云端任务没有落地或超时**，而不是"下载接近完成时中断"。

### 1.4 根因（已分层证实）

1. **官方 APP 链路（用户实际看到的现象）**：云侧未向设备下发 upgrade。设备 mqlink/ctcapd 均无命令痕迹；官方云对本设备回 `{"code":1004,"msg":"no recommend version been found!"}`（`/tmp/macc_upgrade.log`，Nov 16 17:28）。APP 仍播放动画进度，最终报失败。**真机已证。**
2. **我们自己的链路（潜在风险，本设备未触发）**：`shinya_upgrade.lua` **不在设备上**（`dev_sta get -m shinya_upgrade` 返回空载荷），所以我们的下发根本没有执行器。即便部署了它，`-m 180` + 不看退出码 + 不校验 md5 + 非空即刷的实现也会把半截包刷进去——39566318 字节在 180s 内需 ≈1.76Mbps 持续吞吐，而厂商 wrapper 的 900s 只需 ≈0.35Mbps。**代码级推断，缺陷成立但非本次现象成因。**
3. eweb 之所以能成功：它走厂商 wrapper（`-m 900` + md5 + 失败删包），且是设备本地直接下发，不依赖云推送。

与既有审计一致：`docs/APP_FIRMWARE_UPGRADE_AUDIT_2026-11-13.md` 已写明"不能只因文件非空就刷写"。

---

## 2. 原理

### 2.1 Beta 链路（eweb "Beta在线升级" 页）

| 环节 | 实际执行 | 证据位置 |
|---|---|---|
| 检测 | `dev_sta get -m ehr_beta_upgrade {"action":"version"}` → lua 内 `luci.sys.exec("ehr_beta_upgrade --version-check self")` | `tree/new/usr/local/lua/dev_sta/ehr_beta_upgrade.lua:235-244`；`usr/lib/lua/ehr_beta_version.lua:44` |
| 检测用 sh | `/usr/sbin/ehr_beta_upgrade`（3924 字节，真 .sh，仅做版本检查）：`curl -X GET -k --retry 3 --connect-timeout 8 -d '<json>' https://shinya.ruijie.com.cn:7009/service/api/upgrade/recommend/version`，鉴权 `registerKey=md5(ts+privateKey+ts)` | 该脚本第 6-8、43-63 行；privateKey 在 `etc/config/ehr_beta_upgrade` |
| 下发 | `dev_sta set -m ehr_beta_upgrade {"action":"upgrade","keepConfig":true\|false}` | `ehr_beta_upgrade.lua:382-388`；页面调用见 web JS |
| 生成脚本 | 现场写 `/tmp/ehr_beta_upgrade_wrapper.sh`，`chmod +x` 后 `sh` 后台执行 | `ehr_beta_upgrade.lua:114-229` |
| URL 来源 | 模块内再问一次云（`ehr_beta_version.versionRecommend()`），失败回退 UCI `ehr_beta_upgrade.recommend`；取 `downloadUrlTar or downloadUrl` | `ehr_beta_upgrade.lua:85-97`；`ehr_beta_version.lua:60-81` |
| 下载 | 清内存后 `curl -L -k -o /tmp/firmware.tar.gz --connect-timeout 10 -m 900 --retry 3 --retry-delay 3 "$URL" &` + `wait` 取退出码 | wrapper 第 161-175 行 |
| 校验 | curl 非 0/无文件 → state `108` 带 `curl exit code: N`，**删包**；md5 不符 → state `110` 带 expect/actual，**删包** | wrapper 第 179-196 行 |
| 刷写 | `sleep 2; sysupgrade $SYSUPGRADE_FLAG /tmp/firmware.tar.gz`，`SYSUPGRADE_FLAG = keepConfig and "" or "-n"` | `ehr_beta_upgrade.lua:100`；wrapper 第 199-209 行 |
| 进度 | `dev_sta get -m ehr_beta_upgrade {"action":"progress"}` 读 `/tmp/ehr_beta_upgrade_status`；下载中按字节比计算并**封顶 99**；速率在 `/tmp/ehr_beta_upgrade_speed` | `ehr_beta_upgrade.lua:278-339` |
| 取消 | `dev_sta set -m ehr_beta_upgrade {"action":"cancel"}` → kill wrapper 与 curl PID（`/tmp/ehr_beta_upgrade.pid`）→ 删包、删速率文件，state `101` | `ehr_beta_upgrade.lua:53-68,389-393` |
| 日志 | `/tmp/ehr_beta_upgrade.log`（sh debug）、`/tmp/debug/ehr_beta_upgrade.log`（lua debug） | 同上 |

页面侧对应调用（`fwdiff/web/new/static/js/`）：`systool3306d23997bfc493a6b8.js` 的 `$apiArr({module:"ehr_beta_upgrade"/"ehr_beta_upgrade.set",data:{action:...}})`；`app12d5f16b2a97ff8d8b25.js:47` 的模块→UF 映射表（`"ehr_beta_upgrade.set":"dev_sta set -m ehr_beta_upgrade $data"`）。

### 2.2 官方（正式版）链路：四条子通道，互不相同

**B1 设备自检 / 定时升级**（`tree/new/usr/lib/lua/tools/version.lua`）

- 检测：`version.getNewVersion('self')` → `luci.sys.exec("macc_upgrade.sh --version-check self")` → `curl -X GET -k --retry 3 --connect-timeout 8 -d '<json>' http://firmware.ruijienetworks.com/service/api/upgrade/recommend/version`（`version.lua:90-93`；`sbin/macc_upgrade.sh:7-8`）。
  注意：`etc/config/upgrade` 里的 `recommend_url`（`api.cloud.ruijie.com.cn`）属于 `version.lua:102-137` 的**注释块**，现行代码不走它。
- 触发：`usr/lib/lua/smb/immediateUpgrade.lua`（挂在 rg-task）比较 `Release(数字)`，新才 `version.immediateUpgrade()`；否则写 `115`。`version_auto_upgrade.lua` 负责 tmngt 时间窗（默认 3:33）。
- 下载：`clear_mem()` 先跑 `/sbin/kill_remaining.sh`（**该脚本不在固件树内，未抓到**）＋drop_caches＋`rm /tmp/*.bin /tmp/*.tar.gz`，再 `curl -L -m 180 -k -o /tmp/firmware.tar.gz -s --retry-delay 3 --retry 3 -C - "<downloadUrlTar>"`，随后 `md5sum` 比对（`version.lua:155-227`）。
- 刷写：`if image_supported then`（写成函数引用，恒真，`sysupgrade -T` 形同虚设）→ `console.forkExec("sleep 2;sysupgrade /tmp/firmware.tar.gz")`，**不带 `-n`＝永远保留配置，无"清空配置"选项**（`version.lua:254-272`）。
- 状态：UCI `upgrade.recommend.upgradeState`（100–116/200），**无百分比、无取消**；日志 `/tmp/log/upgrade.log`。

**B2 网页"在线升级"按钮 = 群推**：`wireless.updateVersion({jsonparam})` → `macc_upgrade.sh --version-upgrade '<json>'` → 发 MQTT topic `sys/001/AP/<type>/<sn>/upgrade`（`ubus -t 3 call mqtt upgrade`，retained/q2），下载刷写在 mqtt 侧（`usr/lib/lua/luci/modules/wireless.lua:4-14`；`sbin/macc_upgrade.sh:201-264`）。**这是写操作，不能当检测用。**

**B3 网页"本地升级"**：`POST /api/upload/upgradeLocal?auth=…` 上传 `.gz` → `system.lua upgradeConfirm({isPersist,path})` → `killall dropbear; sleep 2; /sbin/sysupgrade [""|-n] <path>`；`isPersist` 即"保留配置"勾选框；`upgradeCancel` → `rm /tmp/firmware.img`（`usr/lib/lua/luci/modules/system.lua:188-210`）。

**B4 CTC 云直推**：`/usr/sbin/ctc_upgrade <downurl> <chksum> <filesize> <isreboot>` → `curl -I -m 20` 探 HTTP 码 → `curl -L -m 180 -k -o /tmp/upgrade_img.bin -s -w %{http_code} --retry 5 -C -` → 比 filesize → `md5sum` 比 chksum → `sysupgrade /tmp/upgrade_img.bin` → `isreboot=0` 时不重启；日志 `/tmp/ctcapd/ctc_upgrade.log`。

### 2.3 共同末端：保留配置真正发生的地方

`/sbin/sysupgrade`（默认 `SAVE_CONFIG=1`，只有 `-n` 置 0）→ `/lib/upgrade/stage2` → `/etc/sysupgrade.conf` ＋ `/lib/upgrade/keep.d/*`（15 个包）＋ `/etc/sysupgrade.d/config_recover/01-mqlink.sh` ＋ `/usr/sbin/upgrade_compat.sh`（版本变了才跑 `/usr/local/upgrade_compat/*.sh` 迁移）＋ `/sbin/platform_pre_upgrade`（20 字节，`wifi down`）。`sysupgrade` 自身也能从 URL 取配置（`-f`，`curl -4 -L -m 600 --retry 2 -k -o $t -s -w %{http_code} -C - $url`）。

### 2.4 为什么"永远升级中"，看不到成功

- **官方链路有成功字段，但被同一段代码抹掉**：首开机 `/etc/uci-defaults/13_migrate-upgrade` → `migrate_upgrade.lua`，判据 `upgradeState==113` 且 `cat /etc/openwrt_version == upgrade.recommend.versionCode`，成立写 `200`/`升级成功`，否则写 `116`/`升级失败`，`uci:commit`；**紧接着 `os.execute('cp /rom/etc/config/upgrade /etc/config/upgrade')`** 把整个 upgrade 配置恢复出厂（只剩 `upgradeState 100`＋`thresholdValue`＋`matchCnt`），刚写的 200/116 与 `versionCode` 当场消失。
- 所以任何客户端（含官方 APP）能稳定读到的最后一个真状态就是 `113 写FLASH`。
- **Beta 链路根本没有成功字段**：状态在 `/tmp/ehr_beta_upgrade_status`（tmpfs），重启即消失；重启后 `progress` 返回 `{code:0,status:"idle",state:100,progress:0}`，与"从没升过"同形（`ehr_beta_upgrade.lua:279-301`）。
- 唯一残留证据：`migrate_upgrade.lua` 在 cp 之前已往 `/tmp/log/upgrade.log` 写 `migrate_upgrade 升级成功`/`升级失败`，新固件开机后一段时间内可读（64KB rolling）。**本方案不使用它**（见 §3.3），因此 `shinya_exec` 的读白名单无需改动。

### 2.5 "模块存在性"的硬判据

`tree/new/usr/sbin/uf_lua_call:43` 是裸 `local module_call = require(module)`，**没有 pcall**：模块文件缺失时 lua 直接抛错退出、不输出 `@.~` 载荷。于是"模块不存在"与"模块在但云不可达"可严格区分——后者仍会返回结构化 JSON（`{"code":-1,"msg":"Connected server failed!"}` / `HTTP 404 Not Found` / `Invalid Operation object.`，见 `/usr/sbin/ehr_beta_upgrade:52-59,112-115`）。

---

## 3. 实施（定稿规则）

### 3.1 通道判定：由模块存在性决定，一次判定、长期有效

| 探测结果（`dev_sta get -m ehr_beta_upgrade {"action":"version"}`） | 通道 | 呈现 |
|---|---|---|
| 无 `@.~` 载荷 / 非零退出（require 抛错） | 官方 | 走 `shinya_exec` 的 upgrade/upgradeState |
| 有载荷，`new.code==0` 且 `firmwareList` 非空 | Beta | 停留 Beta，可下发（保留配置）＋百分比＋速率＋取消 |
| 有载荷，`firmwareList` 空 / `code:2` | Beta | "无需升级"，**不下探官方** |
| 有载荷，`new.code==-1` / `Connected server failed!` / `HTTP 404` | Beta | "Beta 检测未完成，请稍后重试"，**不降级** |

- 判据只能用"有没有载荷"，不能用 `code`（`code` 是云给的，与模块存在性无关）。
- 通道是设备属性：按 SN 缓存，**缓存键带 `cur` 版本号**，版本变化才重判（热更/升降级可能让模块出现或消失）。
- 有 Beta 模块的设备在 APP 里永远只走 Beta，包括无新版与云故障两种情况。

### 3.2 动作映射

| 动作 | Beta（厂商模块，与 eweb 同一条） | 官方（我们 lua 内跑厂商脚本） |
|---|---|---|
| 下发 | `dev_sta set -m ehr_beta_upgrade {"action":"upgrade","keepConfig":true}` | `dev_sta set -m shinya_exec {"action":"upgrade"}`（detached 跑 `lua /usr/lib/lua/smb/immediateUpgrade.lua`，日志 `/tmp/log/shinya-upgrade.log`） |
| 进度 | `dev_sta get -m ehr_beta_upgrade {"action":"progress"}` → `status/progress/speedHuman` | `dev_sta get -m shinya_exec {"action":"upgradeState"}` → UCI `upgradeState` 码 |
| 取消 | `dev_sta set -m ehr_beta_upgrade {"action":"cancel"}`，仅 `status=="downloading"` 可用 | **无** |
| 百分比 | 有（字节/totalSize，封顶 99） | **无** |
| 配置语义 | 固定保留配置 | 只有保留配置 |

官方状态码表（`version.lua:20-57`）：`104 下载中`、`105 下载成功`、`109 MD5 通过`、`113 写FLASH`、`200 成功`、`106 内存不足`、`107 镜像校验不支持`、`108/111 下载失败/重试失败`、`110 MD5 不符`、`112 下载地址为空`、`103 推荐无效`、`115 未查到大版本`、`116 升级失败`。

### 3.3 终态与渲染复用（不引入第三种文案）

- `113`/`upgrading` 只表示"刷写已启动"：文案"升级已启动，设备正在写入并重启"，**停止轮询**，不无限转圈、不写"成功"。
- 重连后**强制重检**，直接复用现有两种检测态：
  - Beta：`hub/beta` 带 `force=1`（清 ttl=300 缓存与 APP `snapshots`）。Beta 云本就按设备当前 `software` 判断（`ehr_beta_upgrade --version-check self` 带 software/hardware/sns），刷成功后自然回 `code:2` → "无需升级"；仍回 `firmwareList` → "检测到新版本"，可再点一次。**无需我们比版本号。**
  - 官方：下发时记下目标 `versionCode`，重连后只读 `dev_sta get -m sysinfo` 的 `software_version`：等于目标 → "无需升级"；不等于 → "检测到新版本"。**不能用"再下发一次"核对，那是真会刷的写操作。**
- 不报"升级成功"、不报"升级失败"、不报"版本未变化"。失败的自然表现就是"仍然检测到新版本"。

### 3.4 明确不做的事

- 不做"保留/清空配置"双选项；APP 全程只走保留配置。
- 不加"回退正式版请去 eweb"之类提示（有 Beta 模块的设备看不到官方入口是预期行为）。
- 不读 `/tmp/log/upgrade.log` 做厂商自证，不改 `shinya_exec` 读白名单。
- 本轮不打包、不部署、不下发升级。

---

## 4. 代码改动清单（待授权后执行）

### 4.1 HUB（`D:/Github/ShinyaHub`）

| 位置 | 改动 |
|---|---|
| `shinya_hub/api.py:806-816`（`hub/beta`） | 保留为 Beta 检测；区分三态（无载荷 / 云错误 / 无新版），`force=1` 必须真正重取 |
| `shinya_hub/api.py:817-858`（`hub/firmware/upgrade`） | `channel` 由 `official\|custom` 改为 `beta\|official`；`start` 按通道分派到 `ehr_beta_upgrade set upgrade{keepConfig:true}` 或 `shinya_exec set upgrade`；`state` 分派到 `ehr_beta_upgrade get progress` 或 `shinya_exec get upgradeState`；新增 `cancel`（仅 Beta、仅 downloading） |
| 新增通道判定入口 | 一次返回 `{channel, cur, beta:{new}, official:{state,desc,versionCode}}`；缓存键 `SN+cur` |
| `shinya_hub/api.py:822,856-857` | 删除对 `shinya_upgrade` 的两处 UFCommand |
| `shinya_hub/firmware_command.py` | 整个摘除：`dispatch_command` 从 shell 文本抠 URL、`command_result` 四态映射、`upgrade_feedback` 文案表 |
| `shinya_hub/beta.py` | `official_package()`（为自研刷写抠 URL）随 `custom` 分支一并停用；`upgrade_row()` 的 `code 2/6` 判定保留复用 |
| 官方目标版本记录 | 下发时把 `versionCode` 随任务保存，供重连后比对 |
| 测试 | `tests/test_firmware_upgrade.py` 等针对 `shinya_upgrade`/`custom` 的断言改写为双通道；补三态判定、keepConfig 显式 true、cancel 仅 downloading、113 停轮询、force 重检 |

### 4.2 设备侧 lua（`shinya_hub/resources/router_sync/`）

- `shinya_upgrade.lua`：**删除**（含部署清单/同步列表里的条目）。
- `shinya_exec.lua`：官方两动作已实现（`upgradeState` 读 `/etc/config/upgrade` 白名单字段，`upgrade` detached 跑厂商脚本），**本轮无需改动**。
- 影响面：一键体检/健康探测若含 `shinya_upgrade` 探针需同步移除（`fnos_agent_deploy.py` 的 OWNED_MODULES 以实际清单为准）。

### 4.3 APP（`tools/shinya_hub/HubToolsView.java`）

| 位置 | 改动 |
|---|---|
| `:203` `load(force)` | 由 `mode.equals("beta")` 改为按 `channel` 渲染；升级结束后统一走 `force=1` 重检 |
| `:254` `renderBeta` | 拆为 `renderBeta`/`renderOfficial`；Beta 单按钮"升级（保留当前配置）"＋百分比＋速率＋`releaseNotes`；官方单按钮＋状态码文字＋转圈 |
| `:274-280` 下发 | 去掉 `channel:"official"` 硬编码，改由判定结果决定；官方通道记录目标 `versionCode` |
| `:290` `upgradeScope` | 增加通道字段；不再传 `keepConfig`（服务端固定 true，少一个可变输入） |
| `:295` `validateUpgradeAck` | 现在只认 `downloading/flashing/rebooting/running`；改为接受 Beta 的 `{code:0,message:"upgrade started"}` 与官方的 `{started:true,state:{...}}` |
| `:313-331` `watchUpgrade` | 按通道分别轮 progress / upgradeState；`upgrading`/`113` 停止轮询转"等待重启"；重连后强制重检并按检测态渲染 |
| `:315`、`:321-322` | 现有终态判据是 `failed/completed/idle`，且 idle 会显示"设备未报告升级任务，请刷新核对当前版本"——这套四态语义随 `shinya_upgrade` 一并移除；现有 `attempt>=150` × 4s（约 600s）超时保留为兜底，但正常路径应在 `113` 就停 |
| `:254` 现有文案 | "测试版检测尚未完成"/"检测完成"/"暂无可用的测试版固件" 三句可直接复用为 §3.1 的云故障 / 无新版 / 空列表三态，不新增文案 |
| 取消按钮 | 仅 Beta 且 `status=="downloading"` 显示 |

### 4.4 文档

- 本报告；实施完成后在 `docs/APP_FIRMWARE_UPGRADE_AUDIT_2026-11-13.md` 追加"自研刷写已摘除、改走厂商双通道"的结论，避免后续重复重做。

---

## 5. 注意事项

**安全与作用域**
- `keepConfig` 必须**显式传 true**，不可省略：模块默认虽是 true（`ehr_beta_upgrade.lua:79-82`），厂商改默认值就会静默变成清空配置。
- 不下发任何 shell 文本；URL 由厂商模块自己向云取，我们不再从命令串里抠 URL。
- 项目/SN/会话/页面代次校验沿用现有 `upgradeCurrent` 机制；取消与下发都要过同一套作用域检查。
- `cancel` 只在 `downloading` 阶段提供（厂商页面 `canCancel` 同条件）；进入 `verifying/upgrading` 后取消无意义且危险。

**缓存与状态**
- `hub/beta` 现用 ttl=300 缓存，`force` 分支是"先 refresh 再读缓存"，重连后必须确保拿到新值，否则会把升级前的检测结果再显示一遍，看起来像"升级没生效"。
- 通道缓存键必须含 `cur`，否则热更/降级后通道判定会失效。
- 官方 `upgradeState` 会被首开机 `cp /rom/etc/config/upgrade` 抹回 100，**不要把 100 当"失败"**，也不要依赖 200。

**失联与终态**
- `113`/`upgrading` 后设备重启，期间失联属正常；保持"等待/未知"，不得自动报成功（既有审计已强调）。
- 重连窗口与重试上限要定死，避免无限轮询；超时后回到"检测到新版本"态而不是报错。

**厂商侧已知坑（不要照抄）**
- `version.lua:256` `if image_supported then` 恒真，`sysupgrade -T` 校验形同虚设。
- `/sbin/kill_remaining.sh` 被 `version.lua` 引用但不在固件树内，未抓到；官方通道清内存实际只靠 drop_caches + `rm /tmp/*.bin /tmp/*.tar.gz`。
- 官方下载 `-m 180` 偏短，但带 `-C -` 续传且重试 3 次；我们不改厂商脚本，只观察其状态码。
- `macc_upgrade.sh --version-upgrade`（网页"在线升级"）是群推写操作，**不可当检测调用**。
- web JS 引用的 `ehr_beta_auto_upgrade` 模块在本固件中**不存在**，不要接。
- `immediateUpgrade.lua` 的 `Release` 比较是字符串比较，位数不同会误判；HUB 复刻比较时应按数字处理。

**能力损失（已确认接受）**
- 摘除 `shinya_upgrade.lua` 后，APP 只能刷"云推荐给自己这台"的包，任意 URL 刷写能力消失；自定义 URL 兜底一并停用。
- 有 Beta 模块的设备在 APP 内无官方正式版入口（含从 Beta 回退正式版），需要时去 eweb 本地升级。

**尚未验证（不得写成已完成）**
- `dev_sta set -m ehr_beta_upgrade` 经 HUB 的 UF 通道未真机实测（eweb 用的是同一条命令串，但需一次真实下发确认）。
- 官方云推送为何不落地（设备 mqlink/ctcapd 无命令）超出设备侧取证范围，需云侧排查。

**2026-11-18 真机已证（详见 `test/_upgrade_live_20261117/LIVE_EVIDENCE_2026-11-18.md`）**
- 97%/78% 为官方 APP 动画进度，设备侧无下载；§1.3/§1.4 已改写。
- `shinya_upgrade.lua` 不在设备上，`dev_sta get -m shinya_upgrade` 空载荷——模块缺失判据实测成立。
- Beta 检测正常：外层 `code:0`、内层 `new.code:200`；`ehr_beta_version.lua:64` 接受 0 与 200，**但 `tools/version.lua:142` 只接受 0**，官方云若回 200 会永远走 UCI 回退。
- `cur` 末尾带 `\n`（`readfile` 未 trim），版本比对必须先 strip。
- 云端 `versionCode`（`1.279.1518`）与设备 `/etc/openwrt_version`（`1.279.2315`）整串不相等而 Release 号相同——**只能比 `Release(数字)`，不能比整串**。
- `recommend_save` 的 `save_key` 收 `fileSize` 而云端字段是 `size`，UCI 回退路径 `totalSize=0` → **无百分比**。
- `migrate_upgrade` 真机日志 `recommend_version=[]` + `upgradeState` 被抹回 100：§2.4 机制证实。
- `/tmp/debug/ehr_beta_upgrade.log` 懒创建，**不能**用其缺失证明模块未被调用。

---

## 6. 待取真机证据与验收顺序

**失败现场只读采集清单**（下次升级失败后立刻取，不刷写）：

1. `/tmp/ehr_beta_upgrade_status`（status/state/progress/message，含 `curl exit code: N`、md5 expect/actual、`sysupgrade exit code: N`）
2. `/tmp/ehr_beta_upgrade_wrapper.sh`（烧死了实际 URL 与 sysupgrade flag）
3. `wc -c /tmp/firmware.tar.gz` 对比 `uci -q get upgrade.recommend.fileSize`
4. `/tmp/shinya_upgrade/state`、`/tmp/shinya_upgrade/run.sh`（摘除前的对照证据）
5. `/tmp/log/upgrade.log`、`/tmp/debug/ehr_beta_upgrade.log`
6. `df -h /tmp`、`free`（tmpfs 余量）
7. `uci show upgrade`、`uci show ehr_beta_upgrade`

**验收顺序**

1. 只读打通两条 GET：Beta `{action:'progress'}`、官方 `shinya_exec {action:'upgradeState'}`；同时验证"模块不存在"时的无载荷表现。
2. 三态判定回归：模块缺失 / 云不可达 / 无新版，确认不串通道。
3. 经用户授权后，Beta 通道做一次"保留配置升级"（`-m 900` + md5 那条），核对重连后回到"无需升级"。
4. 官方通道在只有官方更新的设备上实测一次，核对 `113` 终态与重连后版本比对。
5. 回归：取消（仅 downloading）、重复提交防护、项目/SN/账号切换、失联恢复。

---

## 7. 证据索引

- 固件：`tree/new/usr/local/lua/dev_sta/ehr_beta_upgrade.lua`、`usr/lib/lua/ehr_beta_version.lua`、`usr/sbin/ehr_beta_upgrade`、`usr/sbin/uf_lua_call`、`usr/lib/lua/tools/version.lua`、`usr/lib/lua/smb/{immediateUpgrade,migrate_upgrade,auto_update_version}.lua`、`usr/lib/lua/luci/modules/{system,wireless}.lua`、`sbin/{macc_upgrade.sh,sysupgrade,platform_pre_upgrade}`、`usr/sbin/{ctc_upgrade,upgrade_compat.sh}`、`lib/upgrade/{stage2,keep.d/*}`、`etc/{config/upgrade,config/ehr_beta_upgrade,sysupgrade.conf,sysupgrade.d/config_recover/01-mqlink.sh,uci-defaults/13_migrate-upgrade}`
- 网页：`fwdiff/web/new/static/js/{app12d5f16b2a97ff8d8b25.js,systool3306d23997bfc493a6b8.js}`
- 我们：`shinya_hub/api.py`、`shinya_hub/firmware_command.py`、`shinya_hub/beta.py`、`shinya_hub/resources/router_sync/{shinya_upgrade.lua,shinya_exec.lua}`、`tools/shinya_hub/HubToolsView.java`
- 既有结论：`docs/APP_FIRMWARE_UPGRADE_AUDIT_2026-11-13.md`、`docs/BE72_BETA_FIRMWARE_CHANGELOG_2026-11-14.md`、`test/_be72_beta_20261114/fwdiff/FIRMWARE_DELTA_REPORT.md`

---

## 8. 实施前核查（2026-11-17 13:56，只读）

用户要求「实际代码改动、文案、资源复用，要仔细核查」。本轮对两个仓库做了逐行取证，
并借 BE72 SSH 做了**只读**真机核查。结论：**§4 的清单不能直接照抄执行**，以下
差异必须先修正，否则会改坏现有链路。

### 8.1 真机只读实证（BE72-PRO，SN G1TD8RX039025）

| 命令 | 实际返回 |
|---|---|
| `dev_sta get -m sysinfo` | `software_version = ReyeeOS 1.279.2315;EW_3.0(1)B11P279,Release(13231518)` |
| `dev_sta get -m ehr_beta_upgrade '{"action":"version"}'` | `{"cur":"...","new":{"totalCount":0,"firmwareList":{},"msg":"no new version available","code":200},"code":0}` |
| `dev_sta get -m ehr_beta_upgrade '{"action":"progress"}'` | `{"state":100,"code":0,"status":"idle","message":"","progress":0}` |
| `dev_sta get -m shinya_exec '{"action":"upgradeState"}'` | `{"state":{"upgradeState":"100"},"action":"upgradeState","observedAt":...}` |
| 文件 | `ehr_beta_upgrade.lua` 11701 字节在；`shinya_exec.lua` 19364 字节在；**`shinya_upgrade.lua` 真机不存在** |
| `uci show upgrade` | `upgradeState='100'`（初始态），`autoUpgrade='0'` |

**对方案的修正**：

1. **无新版的判据是 `firmwareList` 为空 + `msg="no new version available"`，不是 `code:2`**。
   真机 `new.code = 200`，`firmwareList = {}`（**空对象，不是空数组**）。
   §3.1 表格的「code:2」是 HUB `beta.upgrade_row()` 自己构造给
   `isSoftVersionUpdateList` 的输出码，不是设备返回码。三态判定只能看
   「有无载荷 + firmwareList 是否为空」，**绝不能判 code**。
2. `shinya_upgrade` 自研刷写链路在真机**根本不存在**，所以 APP 当前
   `channel:"official"` → HUB `official_package()` 抠 URL → `dev_sta get -m
   shinya_upgrade {action:'start'}` 是**一条永远打不通的死链路**。
   这是本次优化最该先修的部分。
3. 官方 `upgradeState` 返回的是**白名单 state 对象**（当前只有
   `upgradeState:"100"`），不是单个状态码；APP/HUB 必须读 `state.upgradeState`。
4. Beta `progress` 在 idle 时返回 `state:100/status:"idle"/progress:0`。

### 8.2 代码核查：文档描述与实际不符（摘要，24 处）

**HUB（`D:/Github/ShinyaHub`）**

- `official_package()` 挂在 **`official`** 分支（`api.py:838-850`），不是 §4.1:162
  说的 `custom` 分支；`custom` 是 `:851-855` 的 `else`。
- `hub/firmware/upgrade` 实为 `api.py:817-860`；除 `state`/`start` 外**无 cancel**，
  且所有 UFCommand 的 action 都是 **`'get'`**，从无 `'set'`。
- `api.py` 里 `shinya_upgrade` 两处（`:822`、`:857`）；`firmware_command.py` 里
  另有两处（`:39`、`:55`），经 `device/command/sendCmd` / `getCmdResult`
  （`api.py:861-870`）可达 —— §4.1 未列这两条路由。
- 「`firmware_command.py` 整个摘除」会直接破坏 `api.py:824/:833/:865` 的
  `upgrade_feedback` / `dispatch_command` / `command_result` 三处 import，
  必须同步处置；其 `UPGRADE_MESSAGES` 是 **6 键 / 5 种文案**，不是「四态」。
- `hub/beta`（`api.py:806-816`）**原样透传 `cached['value']`**，三态判定目前不存在；
  `code 2/6` 在 `beta.upgrade_row()`（`api.py:802` 使用）。
- `force=1` 是「先 `refresh` 再 `cached_read(ttl=300)`」，**不清缓存**（§3.3/§5
  的措辞不准）。
- `fnos_agent_deploy.py` 的 `FILES` / `SPEEDTEST_FILES` / `OWNED_MODULES` /
  `OWNED_HELPERS` / `router_reference/manifest.json` **均不含 `shinya_upgrade`**，
  所以 §4.2:168/170 的「删除部署清单条目 / 移除探针」**无事可做**。
- `shinya_upgrade.lua` 有**两份**逐字节相同的副本：
  `ShinyaHub/shinya_hub/resources/router_sync/` 与
  `LabProbeApp/tools/shinya_hub/router_sync/`，都是未部署的孤儿源码。
- 既有审计 `APP_FIRMWARE_UPGRADE_AUDIT_2026-11-13.md` 在 **APP 仓库**，
  `ShinyaHub/docs/` 下没有；且其中「设备端 shinya_upgrade.lua 已有」已被
  `BE72_BETA_FIRMWARE_CHANGELOG_2026-11-14.md:100` 用真机取证否定。

**APP（`HubToolsView.java`，§4.3 行号全部核对准确）**

- `channel:"official"` 在 **`:275`**（文档写 `:276`，off-by-one）。
- **`renderBeta` `:256` 已经渲染 `releaseNotes` / `notes`**，不是待加内容。
- 全文**没有 `keepConfig`**；`upgradeScope():293` 只有 `projectId/sn/apSn/action`，
  `channel` 在 `:275` 单独 `put` —— 要改的是两处。
- 终态判据是 `failed/completed/idle` **3 态**，不是文档说的「四态」；
  `downloading/flashing/rebooting/running` 是 `validateUpgradeAck` 的 4 个 ACK 态。
- `mode.equals("beta")` 分散在 `:73/:175/:176/:183/:191/:203/:206`，
  仅改 `:203` 不足；`:175` 的 `if(!mode.equals("beta"))` 甚至让 `:176` 的
  「测试版固件」标题在 beta 模式**不可达**。
- `watchUpgrade` `:322` 有 idle 特判与默认文案
  「正在读取升级进度，请勿断电」，不是「只塞 message」。

### 8.3 文案与资源复用清单（APP 侧，不新增第三种文案）

`HubToolsView.java` **全文无 `R.string` / `R.drawable` / `R.mipmap`**，所有文案
硬编码中文，唯一资源调用是 `:69` 的系统 `status_bar_height`。

可直接复用的检测态文案：

| 原文 | 位置 | 用途 |
|---|---|---|
| `测试版检测尚未完成` | `:254` | 云不可达 / 检测未完成 |
| `未收到有效检测结果，点击刷新可重试。` | `:254` | 上句副标题 |
| `检测完成` | `:254`（`optString("msg","检测完成")` 默认值） | 有结果 |
| `暂无可用的测试版固件` | `:257` | 空列表 / 无需升级 |
| `当前版本` / `未取得版本信息` | `:254` | 版本卡 |
| `一键升级到 ` / `最新版` | `:264` | 升级按钮（仅需改语义，不新增样式） |
| `升级过程中路由器会重启，网络将暂时中断，请勿断电。确认继续？` | `:267` | 确认弹窗，原样复用 |
| `正在下发升级命令…` | `:271` | 下发中 |
| `升级状态等待超时，请重新进入页面核对版本` | `:315` | 兜底超时 |

可复用组件：`:74-80` 的 `surface/text/button/column/row/gap/card`、
`:153-172` `RefreshControl`、`:62/:183` `ProgressBar`、`HubDialog.show`、
`:50` `snapshots`、`:309` `upgradeCurrent`（项目/SN/会话/代次校验）。
`assets/hub-ui/` 下**没有固件/升级专用图标**，不要新增资产。

### 8.4 修正后的实施顺序（仍待真机下发授权）

1. HUB：新增通道判定（按有无载荷 + `firmwareList` 空否，**不看 code**），
   缓存键含 `cur`；`hub/beta` 把 `channel` 放进 `data`（当前是 `reply()` 顶层字段，
   APP 的 `HubToolSession.request` 可能只取 `data`）。
2. HUB：`start` 分派到 `dev_sta set -m ehr_beta_upgrade {action:'upgrade',keepConfig:true}`
   或 `dev_sta set -m shinya_exec {action:'upgrade'}`；`state` 分派到
   `ehr_beta_upgrade get progress` / `shinya_exec get upgradeState`；
   新增 `cancel`（仅 Beta、仅 `status=="downloading"`）。
3. HUB：停用 `official_package()` 抠 URL 与 `shinya_upgrade` 两处 UFCommand；
   处置 `firmware_command.py` 的 import 依赖（保留 `upgrade_row` 的 `code 2/6` 契约）。
4. APP：按 `channel` 渲染、去重已有 `releaseNotes` 渲染、按通道轮询、
   `113`/`upgrading` 停轮询、重连 `force=1` 重检；复用 §8.3 文案与组件。
5. 设备侧两份 `shinya_upgrade.lua` 一并删除；`shinya_exec.lua` 不改
   （`upgrade` / `upgradeState` 已实测存在）。
6. 改写 `tests/test_firmware_upgrade.py`、`verify_upgrade_scope.py` 的断言。

**本轮未做**：未改固件升级的任何代码、未打包、未下发、未刷写。
上述 GET 全部只读，`uci show` 只读。

---

## 9. 实施结果（2026-11-17 14:30，用户授权「实施」）

§8.4 六步已全部落地，**未下发、未刷写、未重新打包 APP**：

1. HUB `shinya_hub/firmware_upgrade.py` + `api.py` 双通道判定/分派/`cancel`；
   `hub/beta` 的 `channel` 移入 data。
2. `firmware_command.py` 去掉 shell URL 抠取与 `shinya_upgrade` 死链，复用新分派。
3. `beta.py` 移除 `official_package()`；`upgrade_row()` 的 `code 2/6` 保留。
4. APP `HubToolsView.java` 按 channel 渲染/下发/轮询，`113` 停轮询，取消仅 Beta
   downloading；零新增文案与资产。`verify_upgrade_scope.py` 更新双通道断言，
   `compile_check.py` 30 sources 编译通过。
5. 两份 `shinya_upgrade.lua` 孤儿源码删除；`fnos_agent_deploy.CAPABILITY_PROBES`
   新增两条只读升级通道探针（一键检测可报告 Beta/官方模块是否可用）。
6. 测试：`tests/test_firmware_upgrade.py` 改写、`tests/test_firmware_dual_channel.py`
   新增、`tests/test_fnos_agent_deploy.py` 补三条断言。

**待办**：BE72 当前无新版，且真机下发需单独授权；APP Java 改动需重新打包
APK 后在真机验收。`docs/APP_FIRMWARE_UPGRADE_AUDIT_2026-11-13.md` 已追加
「自研刷写已摘除」结论。
