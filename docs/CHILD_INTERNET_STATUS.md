# 儿童上网（Child Internet Guard）功能状态

> 生成日期：2026-09-18
> 覆盖范围：LabRelay（路由器端）→ Hub（服务端）→ LabProbeApp（安卓端）三层链路

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  LabProbeApp (Kotlin / Jetpack Compose)                     │
│  ChildInternetRepository → ChildGuardHubApi                  │
│  base = /api/router/child-guard                              │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP（X-App-Token / X-Read-Token）
┌──────────────────────────▼──────────────────────────────────┐
│  Hub (Python / Flask)                                       │
│  hub.py 端点 + child_guard_service.py 命令队列               │
│  CHILD_GUARD_COMMANDS.enqueue → 等待 relay 回 ack（35s）      │
└──────────────────────────┬──────────────────────────────────┘
                           │ 命令队列轮询（X-Hook-Token）
┌──────────────────────────▼──────────────────────────────────┐
│  LabRelay (Rust) child_guard.rs                             │
│  UCI config（child_guard）+ ubus（sniffer.user/policy）       │
│  dev_config / uci 命令 + /etc/init.d/child_guard reload      │
└─────────────────────────────────────────────────────────────┘
```

**关键原则**：App 只认 Hub 的稳定 REST 路径，绝不碰原始 UCI/sniffer 字段；Hub 负责鉴权 + 命令编排；Relay 负责在路由器上执行并回传结果。

---

## 2. 已完成（做了什么）

### 2.1 Relay（Rust）
- `child_guard.rs` 完整动作分发，共 14 个动作（见 §5 接口表）。
- `get_users` / `get_plans` / `get_runtime_state`：读 UCI `user`/`policy` 段 + `ubus sniffer.user/policy` 映射。
- `create_plan` / `update_plan` / `delete_plan` / `set_plan_enabled`：带事务回滚（验证失败/部分写入失败自动回滚）。
- `get_usage`：读 `flow_audit` 的每日流量 + 近 60s 速率，按 user 绑定的 MAC → IP 聚合。
- `list_devices`：读 `/tmp/dhcp.leases`，标注 `guarded`/`uid`/`name`，guarded 排前。
- `add_device` / `remove_device`：设备加入/移除守护（幂等 + 级联清理孤儿 policy）。
- `pause_device` / `resume_device`：一键禁网/恢复。
- `generate_uid` 大写 hex（与官方 APP 的 uid 形态一致）。
- `trigger_reload` 加 `timeout -t 14` 保护（busybox 语法）。

### 2.2 Hub（Python）
- 全部 child-guard REST 端点（读 6 个 + 写 9 个，见 §5）。
- `child_guard_service.py` 的 `_ACTIONS` 白名单补齐（含 `list_devices` / `add_device` / `remove_device`）。
- 命令队列：enqueue → relay 轮询领取 → 执行 → `POST /api/agent/ack` 回报。

### 2.3 App（Kotlin）
- **概览页** `ChildInternetOverviewScreen`：Hero 头图、全设备上网计划总开关、守护设备列表（状态徽章 + 一键禁网 + 更多菜单）、空态、**「添加设备」入口**（本次新增）。
- **设备详情页** `ChildInternetDeviceScreen`：三 Tab —— 上网报告 / 上网计划 / 家长请注意。
  - 报告：今日/近 7 日柱状图 + 应用时长条目。
  - 计划：时段编辑（TimePicker）+ 重复日选择 + 应用白名单（7 大分类）+ 底栏。
  - 应用选择 `ChildInternetAppSelectionScreen`：应用卡片 + 搜索 + 「非库内的应用暂时无法禁用」脚注（**年龄分级已整体移除**）。
- **选择设备页** `ChildInternetDevicePickerScreen`（**本次新增**）：候选设备列表 + 勾选多选 + 加入/移除，已守护设备显示「已守护」徽章 + 一键移除。
- **图标体系**：75 个国内应用图标打进 APK `assets/appicons/`；解析链 = 包图标 → 内置资产 → Homarr CDN → 彩色首字头像（灰色占位方块已绝迹）。
- `ChildGuardHubApi` 已接 12 个端点（capabilities/devices/plans/runtime/createPlan/updatePlan/deletePlan/setPlanEnabled/pause/resume/candidates/addDevice/removeDevice）。

### 2.4 本轮新增（2026-09-18 第二批）

#### (a) 时间选择器改为官方「滚轮」样式（App）
- 旧实现是系统 `android.app.TimePickerDialog`（时钟表盘），与官方 UI 不一致，**已彻底移除**。
- 新增 `LabTimeWheelDialog` + `TimeWheelColumn`（`ChildInternetDeviceUi.kt`）：
  - 小时 / 分钟双列磁吸滚动，`rememberSnapFlingBehavior(lazyListState, SnapPosition.Center)` 居中吸附；
  - 上下各补 2 个等高空行，保证 `00` 和 `59` 也能滚到正中央；
  - 通过 `layoutInfo.visibleItemsInfo` 找视口中心行 → 实时回调选中值；
  - 中央高亮条 + 上下渐隐遮罩（遮罩无 `pointerInput`，不拦截滚动手势）；
  - 标题 + 「取消 / 确定」双按钮，`Dialog(usePlatformDefaultWidth = false)` 承载。
- 计划编辑器改用状态驱动（`TimeFieldTarget.Start/End`），点 `TimeField` 唤起弹窗，确定后 `onPlanChange(...)`。

#### (b) Relay 补推 `sniffer.user` mac（`labrelay v0.2.53`）
- **问题**：固件 reload 的 Lua（`child_guard_reload.lua` → `sniffer_user_add`）在 mac 为空 /
  遇到官方云同步竞争时会**静默返回 -1**，但调用方照样把 `reload` 置 0 —— 丢掉的推送**永不自愈**。
  实测 192.168.5.201 的 uid `9A59FF88…` 在 `sniffer.user` 里 `mac = []`（UCI 里却有 mac）。
- **修复**（三层）：
  1. `ensure_runtime_user_macs()`：reload 后回读 `sniffer.user`，mac 缺失时直接
     `ubus call sniffer.user set` 写回，**保留原有 policy / effect policy**；已齐全则 no-op。
  2. `add_device`：reload 后先 `ensure_runtime_user_macs` 再校验；
     **幂等分支（created=false）也补一次修复**——这正是实测踩到的场景。
  3. `verify_user_presence(uid, should_exist, macs)`：新增 mac 维度校验（旧实现只看 uid 字符串是否出现），
     循环内**自愈一次**，超时报错带上 wanted / runtime 的 mac 明细。
- **新增纯函数 `merge_runtime_macs(existing, wanted)`** + 2 个单测（mac 恢复 / 归一化去重）。
- 版本 `0.2.52 → 0.2.53`。

#### (c) 微信识别根因（详见独立文档）
见 `D:\test\微信识别根因_IPv6_vs特征库_20260918.md`，结论一句话：
**不是特征库问题，是识别面只覆盖 IPv4 而手机微信已主要走 IPv6。**

#### (d) Hub 采集 / 官方数据同源 / IPv6 识别补充（本轮实测）
见 `D:\test\儿童上网_Hub采集与IPv6识别_可行性_20260918.md`。要点：

1. **RDPI 特征库 8 个目标应用全都有**（淘宝 `18-4-2-0`、京东 `18-159-1-0`、快手 `10-146-1-0`、
   哔哩哔哩 `10-141-1-0`、企业微信 `8-1-3-0`、拼多多 `18-158-1-0`、抖音 `10-5-1-0`、
   微信 `7-1-2-0`；全表 379 条）。**问题不在库，在 IPv6 流量不可见。**

2. **IPv6 流量真实且巨大**：`nf_conntrack` 160 条中 32 条为 ipv6，当前 IPv6 会话累计 36.4 MB；
   `ip6tables mangle FORWARD` 计数 4892K pkt / 4060 MB；而 `sniffer_flow` 中 IPv6 行数 **0**。

3. **`sniffer` 内核模块无 IPv6 开关**（只有 `hook`、`sniffer_module_debug`）——此路已排除。

4. **云通道 `mqlink` 不是数据 API**：只有云端下发指令 + `config_notify` 配置同步，
   无用量上报载荷。官方"上网报告"聚合在锐捷云侧。
   **但两边底稿相同**：云数据也来自设备本地同一套引擎 → **我们的天花板 = 官方的天花板**
   （官方 APP 上小红书时长准确、微信不识别，正好印证）。

5. **本地设备确实用路由器 dnsmasq 做 IPv6 DNS**（conntrack 抓到 `dport=53` → 路由器本身），
   即"DNS 辅助归属"在路由器上**可见**（需开 `log-queries`，当前未开）。

6. **首选方案：按 MAC 屏蔽被管控设备的 IPv6**（零猜测、与官方对齐）
   厂商已建好设施：`ip6tables -t filter child_guard` 链里
   `DROP match-set child_guard_ip6_block src`，而该 ipset 是 **`hash:mac`** 类型：
   ```
   ipset add child_guard_ip6_block <设备MAC>
   ```
   一步即可让该设备全部 IPv6 转发被丢 → 应用回落 IPv4 → sniffer/RDPI 精确识别全部 8 个应用
   （含抖音、微信）。粒度仅限该设备、可逆、不影响其他家庭成员。

   > **2026-09-18 更正（重要）**：这一动作**不需要我们实现**。固件
   > `child_guard_reload.lua::child_reload()` 自己会做：扫 `child_guard.<uid>` 时，
   > 只要该 user 任一 policy 的 `app` 列表非空（`is_have_app == true`）就 `ip6_add(mac_list)`
   > → `ipset add child_guard_ip6_block <mac>`。而 `direct_policy()` 正是给
   > `app_allowlist`/`app_blocklist` 计划写 `app` 列表的 —— 所以**"配了应用管控"即自动屏蔽 IPv6**。
   > 唯一边界：纯时段计划（`type=0`，无 `app`）不触发。relay 侧**不能**把这行 `app` 列表当无用数据删掉，
   > `child_guard.rs::direct_policy` 已加 `CROSS-REPO CONTRACT` 注释锁定。

7. **补充方案：IPv6 前缀打标**（**当前不需要，保留为备选**）。实测 AAAA 得到 16 个**独占** /48（企业微信/淘宝/快手/拼多多/京东/B站/微信部分），
   但 **抖音没有任何独占前缀**（`2409:8c50:a00::/48` 被抖音/微信/淘宝/快手/京东/B站共同使用），
   且 `2409:8c1e:75b0::/48` 被微信与拼多多共用 → **共享前缀绝不可单独用于归属**。
   前缀表已生成：`D:\test\_analysis\ipv6_app_prefixes.json`。
   计数机制已实机验证（动态 `ip6tables -t mangle` 链按前缀计数，40s 内真实抓到增量）。
   **但 relay 未接线，且即使接线也无输入**：`sniffer_flow` 里 IPv6 行数恒为 0。
   加上第 6 点已把 IPv6 屏蔽掉，这条路径对"应用管控"场景是冗余的。

8. **report-page 逐项结论**：流量柱状图=**最稳**；上网时长=可近似（`sniffer_flow` 的 `idle` 自累加）；
   应用详情=能做但仅 IPv4；使用记录明细=**路由器无源，做不到**。

#### (e) 上网统计采集实现 + IPv6 屏蔽安全性实测（本轮）
见 `D:\test\儿童上网_上网统计实现方案_20260918.md`。要点：

1. **IPv6 屏蔽对官方特征库零影响（实测对照）**：对 NAS `6c:1f:f7:76:71:04` 做 60s 屏蔽窗口，
   屏蔽前后逐字节快照一致 —— `db.default.json` `7f5ea63c…`、`rules.json` `4c8164ba…`、
   `rdpi -e 微信`=`7-1-2-0`、`rdpi -t`=379 行、`sniffer.user`/`sniffer.policy` md5 全部不变。
   同时 `child_guard_ip6_block` DROP 计数 0→18→36→57→70 pkt 持续增长，NAS 侧 IPv6 全部超时、IPv4 全部 200，
   移除后 IPv6 立即恢复。**屏蔽期间 IPv4 微信流量被 `sniffer_flow` 识别为 `7-1-2` → 整条链路真机闭环。**

2. **不依赖 IPv6 开关的精准识别：TLS SNI 嗅探**。`br-lan` 抓包实测提取到明文 SNI：
   `mp.weixin.qq.com` 22 次、`long.weixin.qq.com` 22 次、`sz.mp.weixin.qq.com`/`mpv6.weixin.qq.com` 各 10 次，
   以及抖音/淘宝/京东/快手/拼多多/B站全系列。抓包内含 IPv6 流
   （手机 → `2409:8c1e:75b0:3007::53:443`），**该流 sniffer 查不到但 SNI 能认出是微信**。
   限制：QUIC(UDP 443) 的 SNI 加密 → 需 DNS 归属兜底。

3. **数据源真相（决定采集设计）**：`/proc/net/sniffer_flow` 是**稀疏快照**（实测连续三次采样只有 1 行，
   而非流量总账本）；第 9 列是**倒计时余量**（每秒 −1，到 0 淘汰），不是"空闲秒"；
   计数**只在新包到达时推进** → 两次采样之差 = 区间流量。
   `/tmp/sniffer_flow_dump.txt` 由固件**每 60s** 写一次且**头部含 epoch + 本地时间**
   → 采集侧无需时区库。**dump 文件即最佳采样源。**

4. **新模块 `labrelay/src/usage_stats.rs`（v0.2.54）**：
   小时桶 `(mac,date,hour)` + 应用桶 `(mac,date,app)`；**在线时间 = 小时桶之和**（与官方算法一致）。
   关键规则：首采样只建基线不计字节／字节取差分且 `saturating_sub`／活跃秒每 mac 每小时只记一次／
   应用活跃秒**只记真正活跃的 App**／间隔 >300s 不计活跃／已统计 dump 块按 epoch 跳过／
   保留期按"最新 N 个日期"淘汰（免日历运算）／落盘 `.tmp`+`rename`／**落盘不含对端 IP 与源端口**。
   另含 `ipv6_to_u128`/`ipv6_in_prefix`/`PrefixAttributor`，**共享前缀返回 None 不猜**。
   对外接口 `tick(keep_days)` 与 `report_json(store, macs, date)`。

5. **验证方式**：本机无 MSVC 链接器 → 定向验证 crate `D:\test\_verify_usage`（`#[path]` 直引源文件），
   `cargo +stable-x86_64-pc-windows-gnu test` → **23 passed / 0 failed**。
   该方式顺带编译检查了 I/O 胶水代码。

6. **官方「上网统计」页字段全部可对上**：在线时间=Σ小时桶；柱状图=`hourly[].minutes`（上限 60）；
   应用时长=`apps[]` 按 minutes 降序；次数=`sessions`。
   官方截图中 **微信 1小时9分钟 是显示出来的** → 说明走 IPv4 时微信能被正确归属，
   我们的 L1（逼回 IPv4）+ 分钟桶正好落在该路径。

7. **⚠️ 该模块当前是惰性的**：`mod usage_stats;` 已登记并通过编译，但**未接线**
   → 尚不会采样、不会写任何文件。

8. **企业方案的共识（用户提出的判断，已确认成立）**：锐捷/TP-LINK 等**不存原始数据**，
   只存预聚合桶并按保留期淘汰（设备侧聚合 → 云端存桶，这正是 `mqlink` 通道在做的事）。
   采样 → 聚合 → 丢弃原始 → 淘汰老桶，是标准架构。

#### (f) Hub 侧聚合表落地 + 对「daily_app_usage 一张表」方案的修正
见 `D:\test\儿童上网_上网统计实现方案_20260918.md` §9。

1. **评审方案的正确与错误**：采样→聚合→只存聚合并按保留期淘汰=✅；
   行数估计（约 11 万/年）=✅（实测 `109 500`）；「永久保存没压力」=✅；
   但「连 1MB 都不到」=❌（**实测 14.4 MB/年**，10 设备×30 应用）；
   且**只有一张 `daily_app_usage` 画不出官方柱状图**，也**没有"日志表"可读**。

2. **修正一：必须两张表**。官方「在线时间 3小时20分钟 = 柱状图各小时之和」，
   所以需要 `usage_hourly(date,mac,hour)`（柱状图+在线时间）**和**
   `usage_daily_app(date,mac,app)`（应用时长）。

3. **修正二（最关键）：长期表不能放路由器**。实测文件系统：
   `tmpfs on /tmp type tmpfs`（内存，重启即失）；持久盘 `/dev/ubi0_2 on /overlay type ubifs`
   仅 **60.1M 总 / 46.4M 可用**，NAND 有擦写寿命。
   → **路由器只做采样+中转，长期表必须落 Hub（NAS 磁盘）**。
   上一轮 relay 的 `/tmp/labprobe_usage_store.json` 是**短期缓冲**（进程重启可续、设备重启不可续）。

4. **已实现 `labprobe-hub/usage_aggregate.py`**：
   `usage_hourly` / `usage_daily_app` 两张表，主键聚簇 + `WITHOUT ROWID`、**无二级索引**
   （去掉冗余 `date` 索引后由 139 B/行降到 **77 B/行**）；
   **`max()` 合并而非 `+`**（relay 推绝对值 → 重试/重复投递/Hub 重启都不翻倍）；
   先全量校验再写库（坏数据不留半批）；
   **分批提交不截断**（早期版本静默丢弃超 5000 行的部分，被单测抓出并修掉）；
   保留期 `usage_hourly` / `usage_daily_app` **均为 10 天**（对齐官方 上网统计 窗口，
   `keep_days` 含当天，即"今天 + 前 9 天"；可用环境变量或请求参数放宽）。
   接口：`POST /usage/ingest`（hook/app token）、`GET /usage/report`（read token）、
   `GET /usage/status`（行数/占用/保留期）。已在 `hub_entry.py` 注册。

5. **实测占用**：10 设备×30 应用×1 年 = 197 100 行 = **14.44 MB**；
   20 设备×1 年 = 394 200 行 = **28.88 MB**（77 B/行）。
   对官方话术建议按 **「十几 MB 每年」** 讲，不要沿用「1MB 不到」。
   只留 10 天时占用可忽略（< 1 MB 量级）。

6. **验证**：`pytest tests/test_usage_aggregate.py` → **29 passed**（连同 child_guard/relay 共 32 passed）。
   > 本机沙箱限制：pytest 默认临时目录被拒（WinError 5），需加
   > `--basetemp='D:\test\_pytest_tmp\bt'`；正式环境无此问题。

### 2.5 本轮新增（2026-09-18 第三批：活跃流量算法 + 10 天保留）

**问题**：只要"有流量"就计时长，会把后台心跳/长连接保活算成使用时长——
手机整夜放床头，微信靠一条 30–60s 的小心跳就能刷出 8 小时。

**已实现：活跃流量闸门（active-traffic gate）**，在 `usage_stats.rs::ingest`：

| 常量 | 值 | 作用 |
|------|-----|------|
| `ACTIVE_MIN_BYTES` | 2048 B | 一个采样区间内该流(上行+下行)必须移动的绝对字节下限 |
| `ACTIVE_MIN_BYTES_PER_SEC` | 32 B/s | 同一闸门的速率表达，防止长区间里"一次小突发买走整段时长" |
| `active_floor_bytes(gap)` | `max(2048, 32×gap)` | 实际闸门 = 取两者较大值 |

- 只有 **delta ≥ 闸门** 的流才把 `(mac, app)` 标记为活跃，进而获得 `gap` 秒时长。
- **新流不再自动算活跃**（旧逻辑 `delta > 0 || is_new` 会让首次出现的流白拿一段时长；
  新流计数器的值包含"我们还没看它之前"的全部历史，只能当基线）。
- 心跳流**字节照样计入** `txBytes/rxBytes`，只是**不买时长**——字节与时长解耦。
- `IngestReport` 新增 `heartbeat_flows`：本区间"有流量但没过闸门"的流数，
  用于真机调参（判断闸门是否过高/过低）。
- 设备级 `在线时间` 与 应用级时长用**同一套活跃集合**，夜间只有心跳 → 两者都接近 0。

**新增 4 个单测**（共 27 passed）：`heartbeat_traffic_alone_buys_no_usage_time`（60 个采样全是心跳 → 时长 0、字节照记）、
`real_interaction_clears_the_gate`（视频流 → 300s）、`a_single_burst_cannot_buy_a_long_interval`
（5 分钟只挪 3 KB → 0）、`active_floor_scales_with_the_interval`。
同时把 3 个原本用"每采样 10 字节"这类**不真实小值**的旧单测改成真实量级
（旧值在新闸门下会被正确判为不活跃，不改就是假通过）。

**保留期对齐官方**：relay `DEFAULT_KEEP_DAYS` 30 → **10**；Hub 两张表 400/1095 → **10/10**。
并把仓库里两个**硬编码旧版本号**（`test_relay_0244_safety.py` 里的 `0.2.48`）改成
"读 Cargo.toml 校验 Cargo.lock 一致"，以后再 bump 版本不会无谓飘红。

**版本**：relay `v0.2.53` → **`v0.2.55`**（含 `usage_stats.rs` 接线前状态；采集任务尚未接线）。

### 2.6 已验证
- relay `v0.2.52` 端到端生命周期冒烟：`add_device` → users 5 → `remove_device` → users 4，全程无超时。
- `get_usage` 端到端验证通过（boundIps / daily / 速率正确）。
- App CI `test-bundle` 构建成功（`testDebugUnitTest` + `assembleRelease`），产物 `build243`。
- **本轮**：relay 改动用「定向验证 crate」(`D:\test\_verify_relay`) 逐字搬运新函数 + 内存假路由器，
  `cargo test` **7/7 通过**（含丢推送自愈、policy 保留、幂等 no-op、精确失败报错）。
  > 本地无法整包 `cargo test`：本机无 MSVC 链接器 / WSL 被安全策略拦截；整包依赖 `ring` 需 C 编译器。
- **本轮**：路由器实测微信识别链路（对照实验，见独立文档）。

### 2.7 本轮新增（2026-09-18 第四批：端到端接线 + CRUD 验证 + UI 对齐）

**这一次是「真的接通了」，不再只是算法就绪。**

| 层 | 改动 | 落点 |
|---|------|------|
| Relay 采样驱动 | 新增 `sync_usage_stats()`：60s 采样 / 5min 推送（仅 dirty）/ 6h 刷 `rdpi -t`；主循环按 `once \|\| sample_due \|\| push_due` 触发 | `labrelay/src/agent.rs` |
| Relay 实时兜底 | `execute` 新增 `get_usage_stats` → `usage_stats_report()`（uid→macs、date 优先级、`source:"relay"`） | `labrelay/src/child_guard.rs` |
| Relay 编译修复 | `add_device` 少传 `verify_user_presence` 第三参 `macs` → **整包编译会失败**，已修 | `labrelay/src/child_guard.rs` |
| Hub 报告端点 | `GET /devices/<uid>/usage-report`：uid/date 校验、`days` 限幅、`source = hub/relay/empty`，**永不报错** | `hub.py` |
| Hub 聚合 | `daily_totals` / `app_totals` 补齐缺口成定宽序列；`compose_device_report(range_days)` | `usage_aggregate.py` |
| Hub 动作白名单 | 加 `get_usage_stats` | `child_guard_service.py` |
| App 数据层 | `ChildGuardHubApi.usageReport()`、`parseChildGuardUsageReport()`（hourly→24 柱 / range.days→10 柱）、`loadUsageReport()` | `ChildInternetRepository.kt` |
| App 报告页 | 标题 + 柱状图（今日 24 / 近 10 天）+ 应用列表 + **刷新按钮** + **口径说明**（官方标题） | `ChildInternetDeviceUi.kt` |
| App UI 诚实化 | 删除伪造年龄分级（`i % 4` 循环值）→ 拿不到真实值就**不显示**；筛选器「有值才出现」 | `ChildInternetRepository.kt` / `ChildInternetModels.kt` / `ChildInternetDeviceUi.kt` |

**页面加载即触发**：进入设备页 → `ensureDevice()` → `refresh()`（拉 devices + usage-report）
→ 默认 tab 就是 `REPORT`。所以**不会出现"登上去全是空数据"**。

---

## 3. 未完成（没做什么）

> 2026-09-18 晚更新：端到端链路**已接线并已验证**（见 §2.7、§3.1、§8.6）。下表只保留仍然成立的项目。

| # | 事项 | 现状 | 影响 |
|---|------|------|------|
| 1 | ~~**应用真实年龄分级没有数据源**~~ → **已整体移除** | 官方「设置向导」第一步是 `适用年龄：`（bundle 字符串表 `021190`），来自**锐捷云端应用目录**；路由器本地 RDPI 表不携带 | **按产品决策彻底删除**：`SelectableAppItem.ageRating` 字段已从模型移除（类型系统上就不可能编造）、`ChildInternetAppSelectionScreen` 的年龄筛选行与筛选谓词已删、空态文案不再提"年龄范围"。`check_kotlin.py` 的 `FORBIDDEN_IN_MAIN` 额外禁掉 `ageRating` / `适用年龄` 防回潮 |
| 2 | **游戏类应用图标源** | 王者荣耀/和平精英/蛋仔派对/元梦之星/迷你世界走「首字头像」兜底 | 游戏类卡片不是真实图标 |
| 3 | **真机验证「选择设备」页** | 代码已编译通过 + 单测通过，但未装 APK 实测全流程 | 加入/移除/回显行为未真机确认 |
| 4 | **user_num 计数漂移** | UCI `user_num` 与实际 user 段数不一致（官方机制自管） | 仅统计显示，不影响功能 |
| 5 | **滚轮时间选择器未上机验证** | 代码已改完（`v0.13.2`/244），本机无 Android SDK 无法编译，`SnapPosition.Center` 吸附手感需 CI 构建后真机确认 | 「开始/结束时间」弹窗样式与滚动体验未验证 |
| 6 | **relay 未构建部署 + C 工具链缺失** | 代码已通（含 CRUD 端到端验证），但本机 `cargo check` 整包被 `ring`（rustls）拦住——缺 mingw `cc1`；仅能对 `usage_stats.rs` / `child_guard.rs` 做定向编译 | 需 CI 出包后部署路由器，改动才在真机生效 |
| 7 | ~~**设备入组时未自动屏蔽 IPv6**~~ → **由固件自动完成（前提：计划带应用列表）** | **更正**：固件 `child_guard_reload.lua::child_reload()` 会扫每个 `child_guard.<uid>`，只要该 user **任一 policy 的 `app` 列表非空**（`is_have_app == true`）就调用 `ip6_add(mac_list)` → `ipset add child_guard_ip6_block <mac>`，把该设备 IPv6 整条 DROP（`hash:mac` + `ip6tables -t filter child_guard -j DROP`）→ 回落 IPv4。我们写的就是这份 UCI，所以**配了"应用管控"计划即自动生效**，无需 relay 额外动作 | 微信/抖音等以 IPv6 为主的应用**能**被识别；对齐官方行为。**仅时段管控（`type=0`、无 `app` 列表）不触发**，此类设备 IPv6 仍不识别 |
| 8 | **IPv6 前缀表可视为"冗余/无输入"** | `PrefixAttributor` + `load_prefixes()` 代码与单测都在，但 `tick()` **不调用**，Hub 也没有写入 `/tmp/labprobe_ipv6_prefixes.json` 的接口 | 即使接线也**无输入**：`sniffer_flow` 里 IPv6 行数恒为 0，归属函数拿不到数据。加上 #7 已把 IPv6 屏蔽掉，保留其为备选即可，**不必接线** |
| 9 | **SNI 嗅探未实现（备选，非必需）** | 方案已实测可行（`mp.weixin.qq.com` 明文可提取）；需限流只取每条新流首个握手包 | 只有在**将来取消 IPv6 屏蔽**时才需要它；QUIC(UDP 443) 的 SNI 加密仍需 DNS 归属兜底 |
| 10 | **relay store 位于 `/tmp`（tmpfs）** | `/tmp/labprobe_usage_store.json` 设备重启即失；长期数据由 Hub SQLite 承载即可，但要明确这是短期缓冲 | 设备重启后当天已累计的桶丢失（重新从 0 开始，`max()` 合并会保留 Hub 侧旧值） |
| 11 | **活跃流量闸门阈值未经真机标定** | `2048 B` / `32 B/s` 是唯一经验参数，目前只有单测合成流量 | 阈值偏高会把真实使用算成心跳；偏低会把保活算成使用 |
| 12 | **「使用记录明细」做不到** | 路由器本地无此数据源，官方那份在锐捷云；逐条会话明细无法复刻 | 只能给「应用聚合时长」，给不了 `05:27-05:36 9分钟` 这种逐条记录 |

### 3.1 距离"官方 100%"还差什么（诚实结论：**数据面已通，只差真机部署**）

| 层面 | 状态 | 说明 |
|------|------|------|
| 采样与聚合算法 | ✅ **已完成** | 分桶、delta 归集、单小时/单日封顶、采样间隔护栏、**活跃流量闸门**、10 天保留，均有单测 |
| 长期存储 | ✅ **已完成** | Hub 两表 + `max()` 幂等合并 + 10 天保留（687 个 Hub 测试全绿） |
| 采样任务接线 | ✅ **已接线** | `agent.rs::sync_usage_stats`：60s 采样 / 5min 推送（仅 dirty）/ 6h 刷新 `rdpi -t` |
| rdpi 应用名预热 | ✅ **已接线** | 同上，`refresh_app_map()` 失败时降级为「只有字节无应用名」并留日志 |
| `get_usage_stats` 动作 | ✅ **已接线** | `child_guard.rs::execute` 新增分支 → `usage_stats_report()` |
| relay → Hub 上报 | ✅ **已接线** | `POST /api/router/child-guard/usage/ingest`，绝对桶值 + Hub `max()` 合并 |
| App 报告页渲染 | ✅ **已完成** | `ChildGuardHubApi.usageReport()` + 24 小时柱状图 + 近 10 天柱状图 + 应用列表 + 口径说明 |
| 入组 / 删设备 / 加规则 / 删规则 | ✅ **已验证** | 真 relay 代码 + 桩路由器工具链端到端跑通（见 §8.6） |
| IPv6 屏蔽（应用管控计划） | ✅ **固件自动生效** | policy 带 `app` 列表 → 固件 reload 自动 `ipset add child_guard_ip6_block` → 回落 IPv4（详见 §3 #7）。relay 侧无需额外动作，但**不可删除 `direct_policy` 里的 `app` 列表** |
| IPv6 屏蔽（纯时段计划） | ❌ **不触发** | `type=0` 无 `app` 列表 → `is_have_app=false` → 不屏蔽；此类设备 IPv6 流量不识别 |
| IPv6 SNI 兜底 | ⚪ **备选未接线** | 仅在取消 IPv6 屏蔽时才需要；当前不必做 |
| 真实应用年龄分级 | ⛔ **已按决策移除** | 数据源在锐捷云端，本地无法获取；模型字段与 UI 已一并删除（见 §3 #1） |
| 「使用记录明细」 | ⛔ **做不到** | 路由器本地无此数据源，官方那份在锐捷云 |

**结论**：算法、存储、端到端链路、CRUD 都已打通并验证，**从「看板全空」变成「有数」**。
IPv6 归属这一项**已不再是缺口**——固件在"计划带应用列表"时会自动屏蔽该设备 IPv6 逼回落 IPv4（§3 #7），
所以微信这类以 IPv6 为主的应用可识别；纯时段计划不触发，属已知边界。
**现在唯一真正的阻塞是（a）真机部署**（本机无 C 工具链，需 CI 出包）；
其余"拿不到"的两项是数据源本身不存在——年龄分级（已按决策移除）、逐条会话明细（在锐捷云）。
「更好」的部分目前有两项是实打实的：**活跃流量过滤**（避免心跳刷时长）
与**实测而非预测**（官方按 KB/s-MB/s 量级预测时长，官方原文 `020607`/`020718`，
我们是每 60s 实测活跃流量）。

---

## 4. 不确定（待确认 / 风险）

| # | 事项 | 说明 |
|---|------|------|
| 1 | ~~年龄分级数据源~~ | **已关闭**：官方该值来自锐捷云端应用目录，本地 RDPI 表不携带；按产品决策整体移除，不再追踪 |
| 2 | NAS SSH 认证失败根因 | `111.23.167.108:13494/13589` 全部账号密码 `AuthenticationException`（疑似账号不对/被限流），仍阻塞 Hub 部署到 Docker。**已解决旁路**：LAN 内 `192.168.5.46:2122`（aarch64 Linux，账号 `18617143092`）登录成功，可用于受控流量实验 |
| 3 | 官方云同步竞争（**与官方 APP 共存的核心风险**） | 官方云每 15 分钟拉一次名单，会在 reload lua 遍历 `child_guard.<uid>` 期间**瞬态创建 mac 命名的小写段**，与 lua 竞争、可把它拖慢数分钟；`timeout -t 14` 是缓解不是根治，边界未完全确定。**结论：同一台路由器上"我们 + 官方 APP 同时管"会共写同一份 UCI `child_guard` 与同一套 `sniffer` 运行时，是同一个管理面，不是两个。** 只用一个（只用我们的 App，或只用官方 APP）时不会冲突；两个都用时以"最后写入者为准"，且可能触发上述竞争 |
| 4 | 本地编译验证能力 | 本机无 Android SDK / 无 Gradle wrapper，Kotlin 只能静态核对，改动靠 CI 确认，无法即时验证 |
| 5 | reload 超时后的半推送状态 | `timeout -t 14` 杀掉 reload 时，iptables 在 lua 之前已重建故安全，但极端情况下是否留半推送态未 100% 确定 |

---

## 5. 接口清单

### 5.1 Hub REST 端点

| 方法 | 路径 | Relay 动作 | 鉴权 | App 端方法 | 状态 |
|------|------|-----------|------|-----------|------|
| GET | `/api/router/child-guard/capabilities` | `get_capabilities` | read | `capabilities()` | ✅ |
| GET | `/api/router/child-guard/devices` | `get_users` | read | `devices()` | ✅ |
| GET | `/api/router/child-guard/devices/candidates` | `list_devices` | read | `candidates()` | ✅ |
| POST | `/api/router/child-guard/devices` | `add_device` | app | `addDevice()` | ✅ |
| DELETE | `/api/router/child-guard/devices/<uid>` | `remove_device` | app | `removeDevice()` | ✅ |
| GET | `/api/router/child-guard/devices/<uid>/plans` | `get_plans` | read | `plans()` | ✅ |
| POST | `/api/router/child-guard/devices/<uid>/plans` | `create_plan` | app | `createPlan()` | ✅ |
| PUT | `/api/router/child-guard/devices/<uid>/plans/<pid>` | `update_plan` | app | `updatePlan()` | ✅ |
| DELETE | `/api/router/child-guard/devices/<uid>/plans/<pid>` | `delete_plan` | app | `deletePlan()` | ✅ |
| POST | `/api/router/child-guard/devices/<uid>/plans/<pid>/enabled` | `set_plan_enabled` | app | `setPlanEnabled()` | ✅ |
| GET | `/api/router/child-guard/devices/<uid>/runtime` | `get_runtime_state` | read | `runtime()` | ✅ |
| GET | `/api/router/child-guard/devices/<uid>/usage` | `get_usage` | read | **`usage()` 缺失** | ❌ 未接 |
| POST | `/api/router/child-guard/devices/<uid>/pause` | `pause_device` | app | `pauseDevice()` | ✅ |
| POST | `/api/router/child-guard/devices/<uid>/resume` | `resume_device` | app | `resumeDevice()` | ✅ |

内部端点（relay agent 轮询）：
- `GET /api/router/child-guard/commands`（X-Hook-Token）→ relay 领取命令
- `POST /api/agent/ack` → relay 回报执行结果

### 5.2 关键返回结构

**`get_usage`**（relay 已就绪，App 未接）：
```json
{
  "ok": true, "uid": "<uid>",
  "usage": {
    "date": "2026-09-18",
    "todayTxBytes": 12345, "todayRxBytes": 67890,
    "todayTotalBytes": 80235,
    "recentAvgTxRate": 0, "recentAvgRxRate": 0,
    "boundIps": ["192.168.1.101"],
    "daily": [{"date":"...","txBytes":0,"rxBytes":0,"totalBytes":0}]
  },
  "verifiedAtEpoch": 1758...
}
```

**`list_devices`**（candidates 页数据源）：
```json
{
  "ok": true,
  "devices": [
    {"mac":"aa:bb:cc:dd:ee:01","ip":"192.168.1.101","hostname":"手机","guarded":true,"uid":"...","name":"..."},
    {"mac":"aa:bb:cc:dd:ee:02","ip":"192.168.1.102","hostname":"iPad","guarded":false}
  ]
}
```

**`add_device`**（幂等）：`{"ok":true,"uid":"...","macs":[...],"created":true|false}`

**`remove_device`**：`{"ok":true,"uid":"...","removedPlans":N}`

**`get_runtime_state`**：`{"ok":true,"runtime":{"uid","policyIds","definedPolicyIds","effectPolicyId","active","blocked","blockedUntilEpoch"}}`

**`get_capabilities`**：`{"ok":true,"capabilities":{"available","version","rdpiEnabled","rdpiAvailable","appControlSupported","supportedDeviceTypes",[...]}}`

---

## 6. UI 结构

```
儿童上网（child_internet_overview）
├── Hero 头图（儿童上网 + 正在守护 N 台）
├── MasterGuardCard（全设备上网计划总开关）
├── 守护设备列表 ProtectedDeviceCard
│   ├── 状态徽章：守护中 / 已禁网 / 当前网络无限制
│   ├── 今日上网时长
│   ├── 一键禁网 / 恢复上网
│   └── 更多菜单（查看详情 / 一键禁网）
├── 「添加设备」入口（标题栏 + 空态双入口）        ← 本次新增
│
├─→ 选择要管理的设备（child_internet_picker）     ← 本次新增
│     ├── 候选设备列表（DHCP 租约，guarded 标注）
│     ├── 勾选多选 + 底部「加入守护 (N)」
│     └── 已守护设备「已守护」徽章 + 一键移除
│
└─→ 设备详情（child_internet_device）
      ├── ChildDeviceHeader（设备名 + 状态 + 禁网开关）
      ├── Tab1 上网报告 ChildInternetReportScreen
      │     ├── 今日 / 近 7 日柱状图 UsageBars
      │     └── 应用时长条目 UsageEntryRow
      ├── Tab2 上网计划 ChildInternetPlanEditor
      │     ├── 时段 TimeField（TimePicker）
      │     ├── 重复日 RepeatDayPicker
      │     ├── 应用分类卡片 AppCategoryCard（7 分类）
      │     └── 底栏（保存计划）
      │     └─→ 应用选择 ChildInternetAppSelectionScreen
      │           └── 搜索 + 图标 + 「非库内的应用暂时无法禁用」（无年龄分级）
      └── Tab3 家长请注意 ChildInternetAttentionScreen
            └── 按天红色警示条（深夜上网记录）
```

**路由注册**：`child_internet_overview` / `child_internet_device` / `child_internet_picker` 三路由，BackHandler + 返回动画（`isReturningFromPicker`）均已覆盖。

---

## 7. 设计逻辑

### 7.1 UCI 配置模型
- 段类型：`config`（全局配置，含 `user_num`、`rdpi_enable`）/ `user`（守护设备）/ `policy`（上网计划）/ `timerange`（时段）。
- 一个 `user` 段 = 一台守护设备，含 `macs`（列表）、`policies`（绑定计划）、`block`/`pause`（禁网/暂停）、`name`。
- `dev_config add` 自动维护 `user_num`（自增）并置 `reload=1`。

### 7.2 reload 机制
- `child_reload(is_reboot)` 只遍历 `user` 段，仅当 `reload=='1'` 或 `is_reboot=="reboot"` 才处理。
- `policy` 通过 `user` 联动推送到 `sniffer.policy`。
- `child_init()` 需 `config.user_num` 存在，否则整段 reload 失败。

### 7.3 关键细节
- **uid**：`generate_uid()` 生成**大写 hex**，与官方 APP 的 `587E8CFB...` 形态一致。
- **add_device 幂等**：请求的 mac 若已被守护，返回 `created=false`，不重复建段。
- **remove_device 级联清理**：删 user 后显式 `drop_runtime_policy(pid)` + `uci delete` 清孤儿 policy 段（reload 本身不删孤儿）。
- **云同步竞争**：官方每 15 分钟 curl 拉名单，会在 reload lua 遍历期间瞬态创建 mac 命名的小写段，可把 lua 拖慢数分钟 → `trigger_reload` 用 `timeout -t 14` 保护。
- **App 端多选加入**：逐台 POST（每台设备一个 user 段），不做多 mac 合并。

---

## 8. 测试逻辑

### 8.1 Relay 单元测试（`child_guard.rs` `#[cfg(test)]`）
约 10 个用例，覆盖：
- `parses_child_guard_export` — UCI 导出解析
- `plan_serializes_and_deserializes_without_raw_rules` — 计划序列化
- `whole_user_replacement_preserves_existing_policy_timerange` — user 整体替换保留既有 policy/timerange
- `runtime_effect_policy_is_normalized` — effect policy 归一化
- `transaction_rolls_back_after_verification_failure` / `..._partial_write_failure` — 事务回滚 ×2
- `app_family_expansion_is_not_one_to_one` — 应用族展开非一一映射
- `dev_identify_metadata_enriches_pc_without_gating_app_control` / `..._name_is_used...` — dev_identify 元数据增强
- `list_devices` hostname 解析断言

### 8.2 App 单元测试（`ChildInternetRepositoryTest.kt`，13 个用例）
- `wechatCatalogExpandsToEveryKnownRdpiSignature` — 微信 RDPI 签名展开
- `planSerializesAndDeserializesStableHubContract` — 计划序列化/反序列化
- `runtimeEffectPolicyMapsNoneBoundAndUnknownPolicies` — runtime effect 映射
- `pcIsNotBlockedByPhoneTabletGateWhenCapabilitySupportsAppControl` — PC 不受手机/平板门槛拦截
- `routerDevIdentifyTypeUsesExistingLabProbeIconVocabulary` — devType 图标词汇
- `capabilitiesParserRequiresOriginalChildGuardAndRdpi` — capabilities 解析
- `routerUidAndStationMacResolveToTheSameProtectedDevice` — uid 与 mac 解析
- `originalWeekdayNamesDeserializeToUiNumbers` — 星期名反序列化
- `candidateListParsesDhcpLeaseShapeWithGuardStatus` — 候选设备解析
- `usageReportParsesOfficialShapeIntoBothTabs` — 上报解析成「今日 24 柱 + 近 10 天柱」
- `heartbeatOnlyAppsDoNotAppearAsZeroMinuteRows` — 0 分钟应用不占行
- `anEmptyReportStillRendersAFullBarFrame` — 空数据也渲染完整柱框
- `malformedUsageRowsAreSkippedRatherThanCrashing` — 脏字段跳过不崩
- ~~`theLocalCatalogNeverFabricatesAnAgeRating`~~ — 年龄分级已整体移除，改为由**类型系统**保证（`SelectableAppItem` 不再有 `ageRating` 字段）+ `check_kotlin.py` 禁值守卫
- `catalogDefaultsStillSelectTheCuratedBaseline` — 移除年龄筛选后默认勾选不被带坏

> 本机无 Android SDK / kotlinc，以上用例**未在本机执行**；用 `check_kotlin.py`
> 做定界符平衡 + 符号解析 + 官方文案一致性静态核对（见 §8.6）。

### 8.3 端到端真机验证（部分完成）
- `add_device` → users 5 → `remove_device` → users 4（生命周期冒烟，无超时）
- `get_usage` 端到端验证（临时挂 mac 验证后还原）
- CI `test-bundle` 构建：`testDebugUnitTest` + `assembleRelease` + APK 校验 + 预发布
- ⚠️ **`usage_stats` 全链路尚未上真机**：采样闸门阈值需跑一天后回看
  `IngestReport.heartbeat_flows` 才能标定。

### 8.4 未做的测试
- 「选择设备」页的真机 UI 流程验证（待装 `build244` APK 实测）
- `usage_stats` 真机采样与上报的真机验证（需先部署 relay `v0.2.55`）

### 8.5 Hub 侧单测（`tests/`，**687 个用例全绿**）
- `tests/test_usage_aggregate.py`：MAC 归一化、`max()` 幂等合并、输入校验、
  按 mac/日期过滤返回官方形状、保留窗口（10 天 = 今天+前 9 天）、
  存储占用测量、`/usage/ingest|report|status` 路由与鉴权
- 其余为 Hub 既有回归（AI / 防火墙 / STUN / WireGuard / RouterRPC / 会话等）

### 8.6 跨语言 & 端到端校验（本机可跑，**全绿**）
都放在 `D:\test\_verify_crud\`（可独立复现，不需要路由器 / Android SDK）：

| 校验 | 命令 | 结果 |
|------|------|------|
| Relay 单测（含 `child_guard` + `usage_stats`） | `cargo +stable-x86_64-pc-windows-gnu test` | **47 passed** |
| **设备/规则 CRUD 端到端** | 同上 | **1 passed**（9 步） |
| `usage_stats` 对 `agent.rs` 的接口契约 | 同上 | **1 passed** |
| Hub 全量回归 | `python -m pytest tests/` | **687 passed** |
| App 静态核对 | `python check_kotlin.py` | **PASSED** |

**CRUD 端到端**是本轮最有价值的一步：它把**真 relay 代码**（`child_guard::execute`，
即 agent 循环真正调用的那个函数）跑起来，只把路由器工具链
（`uci` / `ubus` / `dev_config` / `dev_sta` / `sh` / `rdpi`）换成桩，
然后走完家长能看到的全部动作：

```
加设备 → 列表回显 → 加规则① → 加规则②(不覆盖①) → 停用规则
       → 删规则(路由器上确实消失) → 先配规则后加设备(deviceMac 兜底)
       → 删设备(连带回收规则) → 重复删除安全返回 not_found
```

这样做的直接收益：**发现并修掉了一个编译错误**——
`mutate_membership` 的 `add_device` 分支调用 `verify_user_presence(&uid, true)`
少传了第三个 `macs` 参数，而 `child_guard.rs` 是未提交改动，
`rustfmt` 只能查语法查不出类型，**整包编译会失败**。已修
（补 `&macs`，同时让 runtime mac 缺失可被自愈）。

---

## 9. 部署与版本

| 组件 | 版本 / 提交 | 状态 |
|------|------------|------|
| LabRelay | `v0.2.55`（**`usage_stats` 接线 + 活跃流量闸门 + 10 天保留**；含 `add_device` 编译修复） | 代码完成 + 本机定向验证全绿（47 单测 + CRUD 端到端 9 步），**待 CI 交叉编译后部署** |
| LabRelay | `v0.2.53`（mac 补推 + mac 维度校验） | 代码完成，**待 CI 交叉编译后部署** |
| LabRelay | `v0.2.52`（reload 超时修复 + membership + usage） | 已部署路由器 |
| Hub | `usage_aggregate.py`（usage 端点 + 聚合表，10 天保留） | 本地 **687 测试全绿**，**待部署到 Docker** |
| App | `v0.13.2` build244（**滚轮时间选择器 + 上报数据渲染 + 口径说明**） | 代码完成 + 静态核对通过，**待 CI 构建** |
| App | `v0.13.1` build243（选择设备页 + 图标体系） | CI 构建成功，待真机验证 |

路由器侧实时状态（2026-09-18 抓取）：`sniffer.user` 5 个 user 段，其中
uid `9A59FF88…`（192.168.5.201）mac 已恢复为 `1a:9c:c5:c5:b7:bb`（手工修复仍生效）。

**官方口径对照（来自官方 bundle 字符串表，用于校准我们的文案）**

| 官方字符串 | 编号 | 我们的处理 |
|-----------|------|-----------|
| `关于"上网时长"和"应用详情"计算方式说明` | `019899` | 直接作为口径说明标题（已用脚本核对一致） |
| `-- 仅可查看近10天的记录 --` / `近10天` / `最近10天` | `019019` `019020` `020680` | 保留窗口 = 10 天，与官方一致 |
| `内网上网时长` | `019945` | 口径说明里明确「统计含内网使用」 |
| `今日上网时长` | `019724` | 报告页标题一致 |
| `按照本设备上的“动态应用app检测”来预测…时长` | `020607` | 官方=预测；我们=实测（口径说明里写清差异） |
| `来计算上网时长，例如一般微信的流量是KB/S级别…` | `020718` | 同上 |
| `适用年龄：` / `选择平台` / `选择禁止的应用` | `021190` `021191` `021193` | 官方向导第一步；数据源在云端 → 我们**不猜**（见 §3 #1） |
| `仅允许添加4台设备` | — | 官方上限 4 台；我们不设该上限（有意放宽） |
| `规则超过限制条数(10条)` | — | 官方计划条数上限 10；我们未设上限 |

---

## 10. 建议的下一步优先级

**接线已完成，下一步是把改动推到真机。**

1. **CI 出包 + 部署**（最高优先，否则本机改动都不生效）：
   App `build244` + relay `v0.2.55` + Hub `usage_aggregate.py` 一起上。
2. **真机验证「选择设备」页**：加入 / 移除 / 回显 + 时间滚轮全流程。
3. **真机验证报告页有数**：登录后应看到「今日上网时长 + 24 小时柱状图 + 应用列表」，
   不再为空；同时确认 `usageSourceLabel` 显示的来源（hub / relay / empty）。
4. **真机标定活跃流量闸门**：跑一天后回看 `heartbeat_flows` 与夜间时长，
   确认 `2048 B` / `32 B/s` 是否需要调整（本方案唯一的经验参数）。
5. **IPv6 归属**：**已解决，无需额外动作** —— 固件在计划带 `app` 列表时自动 `ipset add child_guard_ip6_block <MAC>`（见 §3 #7）。
   要验证的是"配了应用管控后微信能出现在应用列表里"；若某设备只有时段计划、没有应用管控，则它走 IPv6 时不会出现应用明细（预期内）。
   SNI 嗅探留作备选，当前不做。
6. **游戏类图标源**。
7. ~~真实年龄分级~~ → **已按产品决策整体移除**（模型字段 / UI / 文案 / 静态守卫，见 §3 #1）。

---

## 11. 附录：微信识别根因

`D:\test\微信识别根因_IPv6_vs特征库_20260918.md`

一句话：**不是特征库问题，是识别面只覆盖 IPv4，而手机微信主流量已是 IPv6**。
微信族特征（`7-1-2-0/3/12/14`、`微信视频号 10-1-2-0`、`微信支付 18-1-1-0`）在 RDPI 里一条不缺；
NAS 强制 IPv4 访问同一批微信域名 → 8 个域名全部命中 `7-1-2-0`；
改走 IPv6 → 命中 0。`sniffer_flow` 表 100 行里 IPv6 行数 = **0**。
因此我们的 APP 与官方 APP 识别上限相同。
