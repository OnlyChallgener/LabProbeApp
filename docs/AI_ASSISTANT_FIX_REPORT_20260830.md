# AI 助手完整修复与测试报告（2026-08-30）

状态：已修复；经确认用于 `test-bundle/20260830-4` 测试发布，未搭建安卓测试环境。

## 1. 问题原因

### 每日总结日期错误

每日记录工具默认使用了 Python 进程的 `date.today()`。Hub 实际统一使用北京时间，但运行进程的本地日期可能不同，所以 APP 显示 2026-08-30 时，助手可能把“今天”查成 2025-06-15。

另外，路由器上报的 epoch 时间曾按系统时区格式化，事件聚合也只用字符串前缀判断日期；带 `Z` 或跨午夜的事件会被归入错误的一天。

### 腾讯混元 / TokenHub 请求失败

当前 TokenHub 地址本身没有问题。截图中的 HTTP 400 指向另一件事：启用思维模式并调用工具时，TokenHub 要求下一轮 assistant tool-call 消息带回上一轮的 `reasoning_content`；Hub 原来只保留了 `tool_calls`，因此被 TokenHub 拒绝。

腾讯官方文档已说明混元能力迁移到 TokenHub，并提供以下 OpenAI 兼容地址：

- [混元 OpenAI 兼容接口与迁移说明](https://cloud.tencent.com/document/product/1729/111007)
- [TokenHub 混元接入指南](https://cloud.tencent.com/document/product/1823/132252)
- [TokenHub 思维链与工具调用](https://cloud.tencent.com/document/product/1823/130930)

### 长按出现两个弹窗、页面像被锁住

消息气泡同时启用了 Compose 系统文本选择容器和助手自定义菜单，所以会出现灰色系统工具栏与白色应用菜单。两套浮层会同时处理长按和触摸，导致返回和其它设置按钮看起来失效。

消息气泡原先只有单条复制/删除菜单，没有消息级多选；历史对话原先也只有顶部“多选”按钮，没有把长按绑定为进入多选。

### 手动校准与任务用量混在一起

旧实现用一条 `status=adjusted` 的带符号用量行抵消差额。它能暂时把总数调到目标值，但不是独立的供应商后台总量基准；后续删除/维护记录时，统计逻辑容易重新漂移。

## 2. 已完成修改

### Hub（`D:\Github\labprobe-hub`）

- 流式工具调用保存并回传每轮 `reasoning_content`，下一轮请求满足 TokenHub 思维链协议。
- 流式上游中断时清理残留的内容、思维链和工具片段，重试不会拼接上一条半截响应。
- 现有旧混元 OpenAI 地址 `api.hunyuan.cloud.tencent.com` 在首次读取/使用时自动迁移到 `https://tokenhub.tencentmaas.com/v1`；已经是 TokenHub 的配置不变。
- 每日记录工具不再读取进程本地日期，默认交由 Hub 的北京时间 `today_str()` 决定。
- 路由器 epoch 时间按北京时间格式化；事件日期支持 UTC/带偏移 ISO 时间，并按北京时间聚合。
- 手动校准改为 API 配置上的独立基准（含校准时的用量行边界）：
  - 总 Token 以供应商后台手动输入值为准；
  - 校准前后的任务记录不改写、不删除，任务列表逐条用量不变；
  - 校准后新任务继续累加；
  - 删除对话不会减少用量总数；
  - quota 更新与校准仍在同一事务内完成；
  - 兼容旧数据库，启动时增加所需字段。
- 修复每日用量汇总漏加 `total_tokens` 的问题；校准后的新任务在模型分项中只累计一次。
- TokenHub 未声明 SSE 字符集时强制按 UTF-8 解码，中文不再被 `requests` 按 ISO-8859-1 解成乱码；非法 UTF-8 会明确报错，禁止把替换字符写入对话历史。
- 统一多家服务商的真实 usage 字段：
  - DeepSeek：`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`；
  - OpenAI 兼容：`prompt_tokens_details.cached_tokens` 或 `input_tokens_details.cached_tokens`；
  - Anthropic/TokenHub Messages：`input_tokens + cache_creation_input_tokens + cache_read_input_tokens` 才是真实总输入；
  - 没有返回缓存字段的服务商保持“未上报”，不伪造 0 命中。
- 流式 usage 改为“同一次请求取最新累计快照、不同工具轮次相加”。这避免同一 SSE 中重复 usage 帧被多算，同时保证工具选择轮和最终回答轮都计入本次任务。
- 新增缓存统计覆盖量 `cache_reported_input_tokens`，数据库自动迁移旧行；同一天混用多个服务商时，缓存命中率只以已上报部分为分母。
- 手动累计校准不再注入保存当天的趋势柱，避免出现“当天总量几十万、输入/输出却为 0”的假峰值。每日、近 14 天、模型分布和逐任务记录只汇总不可变任务行。
- 最近 14 天后端窗口改为完整的北京时间自然日，不再使用滚动 336 小时截掉最早一天的前半段。
- 新增 WireGuard 网关启停助手工具，直接复用 Relay 的服务状态与启停原语；写操作要求二次确认并固定配置 revision，防止确认前配置已变化。
- “删除额度记录”只清除该配置的额度、手动校准和额度卡显示状态；API 地址、API Key、启用状态、模型配置及每次任务记录全部保留。以后更换同一供应商的模型无需重新输入整套配置。
- 切换 API 配置的模型时清除旧模型额度/校准基准，但保留旧模型任务历史，防止旧模型累计量污染新模型额度。
- 系统提示词要求“今天/昨天”必须使用工具返回的 Hub 日期，禁止模型按自身时钟猜测。

### App（`D:\Github\LabProbeApp`）

- 普通消息气泡移除系统文本选择容器和自定义 Popup；长按只显示页面内的单条操作栏，不再叠加灰色+白色双弹框，也不会拦截页面导航。
- 单条操作栏提供“复制全文 / 局部复制 / 删除消息 / 多选”：复制和删除可直接处理当前消息，只有明确点击“多选”才进入批量模式。
- “局部复制”使用独立、可关闭的文字选择窗口；该窗口内仅启用 Android 系统选词工具栏，可拖动选择任意片段，返回键或“关闭”均可退出，不会与助手操作栏同时出现。
- 多选模式以当前消息为第一项，继续点击其它消息可增减选择；选中气泡有描边和勾选标识，可“复制选定对话”或批量删除，也可随时取消。
- 复制按消息在屏幕中的原顺序生成“我/助手”文本；批量删除逐条等待 Hub 成功，成功项才从页面移除，失败项保留供重试。
- 历史对话条目支持长按直接进入多选并选中当前项；可继续点选其它对话，再执行“复制选定对话”或批量删除。
- 批量删除逐条确认成功；失败的会话不会从列表假删除，并会在对话中提示失败原因。
- 批量删除只删除会话和消息，不删除 `ai_usage` 用量记录。
- Token 趋势固定显示最近 14 个北京时间自然日（包含零值日期），不再以首次使用日为起点生成未来日期。
- 腾讯混元 TokenHub 新配置预设更新为官方 `hy4-preview`，地址保持 `https://tokenhub.tencentmaas.com/v1`。
- 无时区的 Hub 用量时间在 App 显示时按北京时间解释。
- 修复额度校准弹窗按钮被无限高度 Dialog 拉成整页的问题：通用操作按钮改为固定设计高度。
- 额度卡增加“删除”，并有明确二次说明：只删额度/校准卡，不删 API 配置、密钥或任务记录。
- 顶部“累计 Token”标为“校准优先”；旁边输入/输出标为“任务上报”，明确两者口径可能不同。
- 每条任务按服务商实际返回值展示：未返回 usage 显示“Token 未上报，本次不估算”；只返回总量时明确提示没有输入/输出拆分。
- 趋势图缓存口径改为三态：
  - 全部上报：显示已上报范围命中率；
  - 混合上报：显示“命中率 + 覆盖 X / Y 输入”，未上报部分不算未命中；
  - 全部未上报：不显示缓存文案，也不显示误导性的 0%。
- 单日提示把缓存命中、其余输入（已知未命中 + 未上报）、输出分开；部分覆盖时额外显示覆盖分数。

## 3. 统计口径（最终确认版）

| 区域 | 数据来源 | 手动校准是否影响 |
| --- | --- | --- |
| 累计 Token、API 配置额度已用/剩余 | 服务商后台手动校准基准 + 校准后新任务 | **影响，以手动校准为准** |
| 今日 Token、14 天趋势 | Hub 保存的逐任务 usage | 不影响 |
| 模型分布 | 同一趋势周期内的逐任务 usage | 不影响 |
| 任务记录的每次输入/输出/总量 | 对应请求由服务商返回的 usage | 不影响，不回写、不估算 |
| 缓存命中率 | 仅明确上报缓存明细的输入 | 不影响；同时显示覆盖量 |

同一天使用多个服务商或模型时，所有任务都会进入输入/输出总量。假设当天总输入 52k，只有 18k 返回缓存明细，其中 6k 命中，则显示“已上报范围命中 33% · 覆盖 18k / 52k 输入”，不会错误显示为 6k / 52k = 11%。

不是所有服务商和模型都会返回缓存明细。DeepSeek 官方明确返回命中/未命中字段；OpenAI 的部分接口通过 `cached_tokens` 返回；Anthropic/TokenHub Messages 使用缓存读写字段；其它 OpenAI 兼容网关可能省略这些字段。参考：

- [DeepSeek Chat Completions usage 字段](https://api-docs.deepseek.com/api/create-chat-completion/)
- [OpenAI API usage / cached_tokens](https://platform.openai.com/docs/api-reference/batch/object?api-mode=responses)
- [腾讯 TokenHub Anthropic Messages Token 用量](https://cloud.tencent.com/document/product/1823/135874)
- [腾讯 TokenHub 混元调用指南](https://cloud.tencent.com/document/product/1823/132252)
- [腾讯 TokenHub 提升 Cache 命中率指南](https://cloud.tencent.com/document/product/1823/131410)

## 4. 验证结果

- `python -m py_compile assistant/api.py assistant/storage.py assistant/tools.py hub.py`：通过。
- Hub 全量 Python 测试：**553 passed**（`python -m pytest -q`，144.87 秒）。
- 本轮新增/覆盖：
  - TokenHub `reasoning_content` 工具调用回传；
  - 旧混元地址迁移到 TokenHub；
  - Hub 北京时间默认日期；
  - UTC 午夜跨日事件归档；
  - 手动校准基准独立于会话删除，且后续任务继续累加；
  - 每日总量、校准基准和校准后模型用量不漏记、不重复；
  - 校准并发与 quota 原子性回归。
  - TokenHub SSE 中文 UTF-8 与非法编码拒绝；
  - DeepSeek/OpenAI/Anthropic 三类缓存 usage 归一化；
  - 混合服务商同日缓存覆盖率不把未上报输入当未命中；
  - 同一流累计 usage 快照不重复计数、跨工具轮次正确相加；
  - 手动累计校准不污染今日/每日趋势/输入输出拆分；
  - 删除额度记录保留 API 配置、密钥和任务历史；
  - WireGuard 查询、确认启停、revision 变化拒绝执行。
- Android/Gradle 未运行，符合“本地只能运行 Python 测试、不能搭建安卓测试环境”的要求；App 变更已做静态代码检查。
- App 静态验收确认：普通聊天气泡中已无 `SelectionContainer`、`Popup` 或单条浮动菜单路径；唯一的 `SelectionContainer` 只存在于用户主动打开的局部复制窗口。单条操作、多选和历史会话多选状态相互隔离，Hub/会话切换会清空状态，避免跨会话误删。

## 5. 发布前说明

- 当前没有新增“一键升级固件”能力；TokenHub 只修复了请求协议和地址兼容，不会替用户猜测不存在的升级 RPC。
- 已配置的模型 ID 不会被强制替换。若旧配置仍使用供应商已下线的模型名，需要在 AI 设置中改成 TokenHub 当前可用模型（例如官方文档中的 `hy4-preview` 或账户实际开通的模型）。
- 已按确认创建并推送同名 `test-bundle/20260830-4` 标签到两个仓库，触发 GitHub 测试发布。
- 本轮没有运行 Gradle、Android SDK、模拟器或真机测试；Android APK、Hub 镜像与 LabRelay 包由 GitHub CI 构建和验证。
