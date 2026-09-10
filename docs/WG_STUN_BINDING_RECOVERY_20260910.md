# WG / STUN 自动绑定恢复与错误状态修复

## 现场问题

- 同为 UDP 51820 的两条 STUN 规则中，WireGuard 自动绑定的规则不能直接改目标或删除；原有手动规则可以正常操作。
- WireGuard 配置删除后，自动 STUN 仍可能被 Hub 中的残留 `endpointProfiles` 引用，App 持续阻止修改、停用和删除。
- STUN 操作失败状态由共享操作中心保存，WireGuard 页面也会显示该错误，且缺少明确确认/清除生命周期。
- App 停用 WireGuard 服务端或修改监听端口时显示“上游服务暂不可用”；旧 UI 无法区分请求未到 Hub、Hub 已受理但回查失败，以及 Agent 尚未应用。

## 修复原则

1. 绑定完全按 STUN rule ID、WG profile/peer ID 处理，禁止按名称、51820 端口或列表顺序猜测。
2. 停用 WireGuard 只停止服务，不代表解绑；删除、转手动、改绑必须先由 Hub/Agent 确认旧引用已移除，再更新手机本地配置。
3. 手机本地仍有 WG 配置引用时，继续保护 STUN。仅 Hub 存在引用而本地无对应配置时，提供用户明确确认的“清理残留绑定”恢复操作，不后台静默删除。
4. 显式恢复只移除引用目标 rule ID 的 endpoint profile 及其同 ID peer，保留其他配置、STUN 规则及未知字段，并使用修订冲突重试和 Agent 应用回执。
5. 自动创建 STUN 遇到超时或连接中断时不直接重放；重新读取并按创建前后 ID 核对，只有唯一的新兼容规则才能认领。
6. 自动绑定规则标注“WireGuard 自动管理”；同端口但未绑定的手动规则不受限制。
7. 操作错误只在所属页面/弹层显示；支持确认关闭，新操作成功后旧错误不再恢复。
8. WireGuard 变更按“提交前 / Hub 受理 / Agent 应用确认”分阶段报告。Hub 已受理后的回查 502 属于待核对状态，不显示“未更改”，也不自动重放 PUT。

## Hub / Relay 契约核对

- App 与 Hub 均使用 `/api/wireguard/server` 和 `expectedRevision`，WireGuard 配置及 Agent `applyResult` 字段一致；未发现路径或请求字段未对接。
- Hub 的 WireGuard GET/PUT/DELETE 处理通常返回 200、400、401 或 409，不主动生成 502；Relay/Agent 通过命令轮询和回执工作，不承接 App 的 WireGuard HTTP 请求。
- 因此立即出现 HTTP 502 时优先检查实际 Hub 地址、反向代理 upstream、Hub 进程和部署版本。Hub 返回 200 后长期无应用回执时，再检查 Relay/Agent 在线状态、令牌、版本和执行结果。
- App 仍需容忍提交后的临时回查失败：保留待核对状态并通过只读刷新做结果对账，禁止因状态不明而盲目重复写入。

## 分工与阶段

- 核心：绑定读取、旧引用解除、残留恢复、自动创建不确定结果核对及纯模型测试。
- WireGuard UI：远端先行的保存/删除流程、状态范围隔离、端口/停用说明和完整绑定选择。
- STUN UI：自动规则标识、残留恢复确认、错误确认/清除及静态契约测试。
- 主任务：跨文件整合、边界复核、Python 验证、GitHub Android/Kotlin 构建、提交与日志收尾。

## 验收标准

- PASS：自动与手动的 51820 规则可按真实 rule ID 区分；手动规则正常改删。
- PASS：正常 WG 删除、转手动或改绑后，旧 STUN 不再被旧引用阻止。
- PASS：历史残留引用只能经明确确认清理；有本地 WG 配置时仍阻止直接清理。
- PASS：关闭 WG 不伪装成解绑；UI 给出明确说明。
- PASS：STUN 错误不出现在 WG 页面；关闭后不回弹；成功操作替换旧错误。
- PASS：自动创建响应不确定时不生成第二条重复规则。
- PASS：WireGuard 修改若 Hub 已受理但 Agent/回查暂不可用，显示“已提交，待核对”，保留原本地配置并允许只读刷新；不会误报“未更改”或自动重复提交。
- FAIL：按名称/端口批量删除、静默清理合法配置、先删本地再等远端、失败后丢失原配置或错误长期跨页悬挂。

## 验证边界

- 本机只执行 Python 静态回归和差异检查，不安装或运行 Android SDK、Gradle、模拟器。
- Kotlin 单元测试、Android Release 编译与签名 APK 检查由 GitHub Actions 执行。
- 自动化测试不等同于真实 Hub/Agent 数据修复、路由器防火墙状态或 WG 握手；这些必须通过测试 APK 与现场数据验收。

## 实施日志

- 2026-09-10：基于 `codex/wg-stun-operation-sync` / `4c448f5` 建立本修复计划；工作区干净。并行拆分核心、WG UI、STUN UI 三个互不重叠的修改范围。
- 2026-09-10：只读核对 `labprobe-hub` 的 WireGuard 路由、服务实现和 Relay/Agent 命令回执；确认代码契约已对接，502 更符合部署入口/反向代理/Hub 可用性问题。将 App 的提交阶段与待核对状态纳入本轮修复。
- 2026-09-10：实现按 STUN rule ID 读取服务端引用、显式清理仅服务端残留、自动配置远端先行的编辑/改绑/转手动/删除事务，并按同 ID endpoint profile/peer 精确移除；STUN 规则本身不由该事务删除。
- 2026-09-10：WireGuard 提交前读取失败归类为“未提交”；PUT 502、超时或连接中断归类为“提交结果待核对”且不自动重放；Hub 已返回 revision、Agent 尚未回执时归类为“Hub 已保存，等待 Agent”。刷新会先比对待核对的目标设置，避免把仍然生效的旧配置误报为新修改已确认。
- 2026-09-10：STUN 页按本地与服务端真实绑定 ID 标记“WireGuard 自动管理”；本地仍引用时继续保护，仅服务端残留时要求二次确认。WG/STUN 错误按操作前缀隔离，支持关闭，成功刷新后不再回弹。
- 2026-09-10：WireGuard 绑定候选仅接受启用、WireGuard 服务类型、UDP、当前网关目标和当前监听端口的规则；不再截断候选，同端口的非 WireGuard 或手动未绑定规则不会被误认作自动规则。
- 2026-09-10：本机执行 `python -m unittest discover -s tools -p "test_*.py" -q`，35 项通过；`git diff --check` 通过。未安装或运行本地 Android SDK、Gradle、模拟器。
- 2026-09-10：首次 GitHub CI `34449505345` 在 Kotlin 编译发现四处中文紧邻字符串模板变量的插值语法错误；已统一改为显式 `${action}`，本地仍只执行 Python 与差异检查，等待下一次 GitHub CI 复验。
- 待记录：代码提交、GitHub Kotlin/Android 构建、Release APK 结果及现场部署核对。
