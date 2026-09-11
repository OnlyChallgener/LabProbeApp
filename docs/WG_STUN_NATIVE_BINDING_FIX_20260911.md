# WireGuard / STUN 原生映射绑定修复

## 现场现象

- 在 WireGuard 中选择“使用 STUN”后，App 能自动创建穿透规则并显示公网地址，但 WireGuard 配置始终无法保存。
- Hub 返回：`WireGuard STUN Profile 必须使用路由器原生端口映射`。
- 失败后的配置已保存在手机本地，页面持续显示 Agent 同步失败。

## 根因

App 自动创建的 WireGuard STUN 规则使用了 `router_self + 127.0.0.1`。Hub 会把这种目标归类为 `relay_proxy`，即由 Agent/LabRelay 本地代理转发。WireGuard 服务端实际运行在路由器上，Hub 因而只允许绑定 `router_native`：目标必须是当前路由器 LAN IPv4，且 UDP 目标端口必须等于 WireGuard 监听端口。

公网地址已经出现只代表 STUN 映射建立成功，不代表该规则满足 WireGuard 的绑定契约。

## 修复内容

1. 自动创建 WireGuard STUN 时改用当前路由器 LAN IPv4，不再写入 `127.0.0.1`。
2. WireGuard 候选规则必须同时满足：已启用、WireGuard、UDP、当前监听端口、`router_native`、目标为当前路由器。
3. 对已由旧版 App 绑定的错误规则做精确修正：仅当 rule ID 已绑定，且规则完整符合 WireGuard/UDP/当前端口/`router_self`/`127.0.0.1`/非原生映射特征时，原 ID 改为路由器原生映射后继续保存。其他手动规则不自动修改。
4. 旧规则修正请求结果不确定时只回读核对，不自动重复写入。
5. Hub 明确返回 4xx 拒绝时显示“Hub 拒绝，未更改”；只有超时、连接异常或 5xx 才显示“Hub 未确认接收”。

## 验收标准

- PASS：新建“WireGuard + STUN”后，STUN 目标显示为路由器 LAN IPv4 和 WireGuard 监听端口，WireGuard 配置可被 Hub 接收。
- PASS：已留下的旧 `127.0.0.1` 自动绑定可在再次保存时按原 rule ID 修正，不新增重复规则。
- PASS：其他手动 STUN 规则不被修改，也不会因为端口相同被 WireGuard 自动认领。
- PASS：公网地址尚未刷新时 UI 可以显示等待状态，但不得把 Relay 代理型规则当成可用绑定。
- FAIL：自动规则仍显示 `127.0.0.1`，或仅凭公网地址/相同端口即允许绑定。

## 验证边界与日志

- 本机只运行 Python 静态回归及 Git 差异检查，不安装或运行 Android SDK、Gradle、模拟器。
- Kotlin 单元测试、Android Release 编译、签名与 APK 制品由 GitHub Actions 完成。
- 2026-09-11：根据现场截图复现配置链路并核对 App/Hub 契约，确认自动创建 payload 与 Hub 原生映射校验冲突。
- 2026-09-11：完成自动创建、旧绑定精确修正、候选筛选、错误语义及 Kotlin/Python 回归用例修改。
- 2026-09-11：首次 GitHub CI 完成 Kotlin/Release 编译，但一个旧绑定测试数据缺少 `enabled/serviceType` 字段而失败；已补齐真实规则必需字段后重新提交验证。
- 2026-09-11：GitHub CI `34561413744` 通过：38 项 Python 静态回归、242 项 Kotlin/Android 单元测试与 Release 编译全部成功。该次为手动分支验证，未执行签名 APK 上传步骤。
- 2026-09-11：GitHub Test Bundle `34562081484` 通过：242 项 Kotlin/Android 单元测试、签名 Release APK 构建、APK 签名校验与制品上传均成功；制品为 `LabProbe-v0.12.0-build241-test-apk`。
- 2026-09-11：测试发布 `test-bundle/wg-stun-native-20260911` 完成。GitHub Test Bundle `34568223418` 通过并发布预发布 APK `LabProbe-v0.12.0-build241-test.apk`；SHA-256：`b9fe1e9e9eeaafd126f04abc2b048446f3ddc5c31e795e30c7a8adc522327a24`。
