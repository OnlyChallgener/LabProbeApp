# AI 助手问题修复与测试报告

日期：2026-08-30  
分支：`codex/ai-assistant`  
状态：**全部改动仅保留在本地，未提交、未推送、未触发 GitHub 测试发布**

## 1. 结论

本轮按 P0/P1/P2 优先级完成了 AI 助手对话、SSE、确认操作、Hub 切换、通知、Token 用量、密钥轮换和隐私边界的集中修复。

- Hub 全量 Python 测试：**539 passed**。
- 新增 AI 硬化专项测试：**17 passed**。
- Python 编译检查：通过。
- App 端按要求未搭建或调用 Android SDK、Gradle、模拟器、设备测试环境；完成源码与两端接口静态验收。
- 两个仓库均未提交，等待确认后再推送并走 GitHub 测试发布。

## 2. 产品口径确认

### 2.1 14 天用量图

采用用户提出的口径：**数据不足 14 天时，从第一个真实用量日开始，向后补足 14 个北京时间自然日**。

例如首次使用日是 2026-08-28，则图表范围为 **08-28 至 09-10（含首尾，共 14 天）**；尚未发生的日期显示 0。已有数据跨度达到 14 天后，显示最新 14 天。

图表标题改为真实起止日期，不再固定显示错误的“自昨日起 14 天”。柱高使用当天 `totalTokens`；人工校准产生、但无法拆分为输入/输出的差额显示为“校准/其他”。模型分布使用与柱图相同的日期窗口。

### 2.2 用户手动校准 Token

保留并加强用户手动校准权限。用户可根据 API 服务商后台的真实累计用量，把某个 API 配置的累计 Token 修正为目标值，并可同时更新或清空额度。

- 校准值和额度在同一个 SQLite `BEGIN IMMEDIATE` 事务内更新，避免并发校准叠加错误。
- 目标值与当前值相同时不写无意义记录。
- 校准差额计入 Token 总量和图表，但**不计为一次任务、不伪装成失败请求、不进入最近任务列表**。
- App 在切换 Hub 后立即清空旧 Hub 的用量状态，避免短暂展示另一台 Hub 的数据。

## 3. 分优先级、分模型修复结果

### P0：页面不可用、误执行或数据串线

负责：GPT-5.6 Terra（App）、GPT-5.6 Sol（Hub），主任务交叉整合。

1. **“满天复制/删除菜单”与页面卡死**
   - 消息菜单状态从每个气泡内部提升为页面唯一状态，同一时刻只允许一个菜单存在。
   - 消息列表使用稳定身份，删除或重组时不再让旧菜单附着到其他气泡。
   - 菜单可以正常关闭、复制、删除；不会继续叠出多个高优先级弹层阻塞返回和导航。

2. **Hub 切换隔离**
   - API 客户端、会话 ID、消息、历史加载、确认卡、用量页状态全部按 Hub URL + token 身份隔离。
   - 合并“清空旧 Hub”和“恢复新 Hub”为同一个顺序任务，消除竞态导致的永久“恢复中”。
   - 发送中禁用新对话、历史和设置入口，流回调只允许写回发起请求的会话。

3. **确认操作恢复、取消和防重复执行**
   - Hub 新增待确认查询、单确认状态查询、取消接口及兼容别名。
   - 重进页面或切换历史会话可恢复仍有效的确认卡。
   - 取消会在 Hub 持久化“用户已取消，未执行”，不再只做 App 本地隐藏。
   - 确认响应丢失时先查 Hub 生命周期；已完成/失败/取消/过期时清卡，未确认状态时明确禁止重复确认。

4. **失败消息不再成为孤儿**
   - 用户消息写库后的配置缺失、工具错误、Provider 错误、空响应和工具轮次上限均持久化对应失败 assistant 消息。
   - SSE error 和非流式错误返回 `conversationId`、`userMessageId`、`messageId`。
- App 对 SSE 事件和 HTTP 4xx/5xx 错误体都解析这些 ID，失败气泡与 Hub 历史保持同一条记录，避免重复气泡或无法删除。
- SSE 建立后的读取超时、代理重置和网络断流统一包装为带会话/用户消息身份的协议异常，不再落入丢身份的通用错误分支。

### P1：SSE、通知、密钥和用量可靠性

负责：GPT-5.6 Sol（Hub）、GPT-5.6 Terra（App）。

1. **Provider SSE 修复**
   - 兼容标准 JSON error 和 SSE `event: error`。
   - 单个异常帧不再直接判整条流失败；若后续有合法数据可继续处理。
   - 标量、全损坏流和真正空流会返回清晰错误，不再只显示 `provider returned invalid SSE JSON`。
   - 上游在上报部分 usage 后断流时，失败记录仍保留断流前 Token，不再低报。

2. **Hub SSE 防卡死与防积压**
   - 流建立后立即发握手，10 秒 keepalive，并设置禁止代理缓冲/变换的响应头。
   - Provider 生产队列改为有界队列；客户端断开后通过停止信号结束生产，防止无限内存积压。
   - Hub 继续使用多线程请求服务；常驻或慢请求不会占死唯一请求线程。

3. **通知链路**
   - 通知采用 App 主界面生命周期内的全局 15 秒轮询，不再伪装为聊天消息，也不会进入模型上下文。
   - 游标按 Hub 身份用 SHA-256 隔离，并兼容旧游标迁移，避免 Hub 切换串通知或哈希碰撞。
   - Android 13+ 未授权通知权限时不推进游标；授权后仍可投递此前未送达通知。Android 13 以下不错误检查该权限。
   - 前台和后台（进程仍存活）均可发布系统通知。

4. **密钥轮换与配置安全**
   - 支持 `APP_TOKEN_PREVIOUS` 解密旧密文，并以 CAS 方式自动改用当前 key 重加密。
   - 无法解密的配置不再显示为“已配置可用”。
   - Provider base URL 拒绝新写入的 userinfo、敏感 query 和 fragment；旧数据库中的敏感 URL 会在使用前规范化并迁移，响应与实际出站均不泄露秘密。

5. **SQLite 与用量一致性**
   - AIStore 连接均显式及时关闭。
   - 用量校准与额度更新原子化；并发写入测试锁定目标值语义。
   - 删除消息后同步修复自动标题、更新时间和空会话元数据。

### P2：边界、隐私和体验一致性

负责：GPT-5.6 Luna 独立只读审查，主任务修正审查项。

- 收藏上下文限制为 16 KiB，并限制字段长度；URL 去除 userinfo、query、fragment，仍保留按 ID/名称操作的能力。
- App client context 设置 32 KiB 上限，避免异常大的本地上下文拖垮请求。
- 通知缺少任务 identity 时生成稳定摘要，避免多个匿名事件被错误合并。
- 流式协程取消会同步取消 OkHttp Call；离开页面不再把正常取消显示成请求失败。
- 模型自动切换保持停用：首个启用配置不可用时显示模型名和真实原因，由用户手动选择其他配置，不会重复烧 Token。

## 4. 新增接口契约

| 用途 | 方法与路径 |
| --- | --- |
| 取消确认 | `POST /api/ai/tools/cancel` |
| 查询会话待确认 | `GET /api/ai/conversations/{id}/confirmations` |
| 兼容待确认查询 | `GET /api/ai/conversations/{id}/pending-confirmation` |
| 查询确认生命周期 | `GET /api/ai/tools/confirmations/{id}` |
| 原子校准用量/额度 | `POST /api/ai/usage/adjust`（可选 `tokenQuota`） |

App 与 Hub 已做静态路径和字段对照。

## 5. 测试结果

### 5.1 Hub Python

| 项目 | 结果 |
| --- | --- |
| 全量 `pytest` | **539 passed in 142.16s** |
| 新增 `test_ai_hardening.py` | **17 passed in 2.21s** |
| Python `compileall` | 通过 |
| 两仓库 `git diff --check` | 通过（仅现有 Windows 换行提示） |

新增硬化测试覆盖：Provider 错误帧、SSE 握手与代理头、有界流关闭、断流 usage、失败消息身份、无配置/工具错误 SSE、Token 轮换、不可解密状态、URL 安全与旧数据迁移、确认取消恢复、原子校准、并发同目标校准、连接关闭、删除元数据、收藏上下文边界。

### 5.2 App 静态验收

按用户限制，**没有运行 Gradle、Android SDK、模拟器或设备测试**。

使用 Python 完成 7 组源码/契约断言，全部通过：HTTP 错误消息 ID、SSE 建立后断流身份、Hub 身份恢复、用量身份隔离、唯一气泡菜单、通知权限与游标、确认/校准接口契约。另完成 Kotlin 源码差异检查，无补丁格式错误。

## 6. 修改文件

### App：`D:\Github\LabProbeApp`

- `app/src/main/kotlin/com/labprobe/app/MainActivity.kt`
- `app/src/main/kotlin/com/labprobe/app/feature/assistant/AiApi.kt`
- `app/src/main/kotlin/com/labprobe/app/feature/assistant/AiNotifier.kt`
- `app/src/main/kotlin/com/labprobe/app/feature/assistant/AiScreens.kt`
- `docs/AI_ASSISTANT_FIX_REPORT_2026-08-30.md`

### Hub：`D:\Github\labprobe-hub`

- `.env.example`
- `README.md`
- `assistant/api.py`
- `assistant/notifications.py`
- `assistant/provider.py`
- `assistant/security.py`
- `assistant/storage.py`
- `assistant/tools.py`
- `tests/test_ai_hardening.py`

## 7. 已知边界与发布前验证

1. 按要求未做 Android 本地编译和真机交互，因此菜单手势、系统通知样式、系统文本选择和页面视觉需由后续 GitHub CI/测试包及真机验收确认。
2. 当前通知拉取依赖 App 进程/主界面组合存活；系统杀掉 App 后不会持续后台拉取。重新启动后会从持久化游标继续，不丢 Hub 中仍保留的通知。若未来要求“进程被杀仍实时通知”，需要单独引入 WorkManager、前台服务或推送体系。
3. 客户端断开时，如果上游正阻塞读取，后台 daemon 最长可能等待 Provider 的 90 秒读取超时；队列已有界且停止信号会阻止继续积压，不会形成无限内存增长。
4. SQLite 本身发生不可恢复的写故障时，无法保证再写入一条失败气泡；正常 Provider、协议、配置和工具失败路径已全部保证消息身份和可恢复历史。

## 8. Git 状态

两个仓库均在 `codex/ai-assistant`，所有修改均未提交、未推送。当前等待用户确认；确认前不创建提交、不推送 GitHub、不打测试发布标签。
