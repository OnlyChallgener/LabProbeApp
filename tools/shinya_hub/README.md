# 星耀家 Hub 测试版

## 2026-10-10 当前状态

按用户要求，官方云与独立 HUB 并行运行，特征库停止改动。

应用宝当前验证包为 `1.2.4-hub.27-dev`，已启用 HUB 地址、账号、密码登录；NAS 已部署
`shinya-hub:oct10-tools-v23`，独立 HTTP 58543、HTTPS 58544、MQTT TLS 58545。
HTTPS 使用绑定 HUB 指纹的专用连接，未放行其他服务器证书。

BE72、BE50 的真实固件诊断启动、进度、结果已通过 ADB；稳定性雷达、今日曲线及最近
一小时速率已接入。临时 SSH 通道失效后，工具和稳定性页仍展示最后成功快照。
截图证明离线显示，不能当成断线时仍有实时数据的证据。

绿联、飞牛、NAS 是独立新增条目，保留原品牌和设备类型。绿联 NAS 图标已在首页、
详情和选择框显示；手动身份按租户、项目、MAC 持久化。飞牛选择的完整保存回读，
以及指示灯按钮的实际切换，还需要恢复远程连接后验收。

正在构建 hub.28，物理裁剪微信/Apple 登录和 Sentry 的原生注册、类及动态库，
抹除已核对的独占短信、微信、推送、教程和游戏反馈字节码实现。
Hermes 表结构及共享部分仍保留，完整裁剪、ARM64 真机、其余无线/信号/儿童配置、
正式官方绑定、WEB/RDP 页面、UU 和户型模拟仍未全部完成。

当前三台设备的临时 SSH 地址已失效，已请求用户更新。MACC 浏览器工具遇到本机
控制工具错误，本轮尚未取得网页抓包。完整进度见
[实施记录](D:/Github/LabProbeApp/docs/shinya-hub-plan-2026-10-09.md)。

## 早期 hub.2/hub.3 记录

以下是早期版本的实现和构建记录，当前状态以上节和报告为准。

基于用户提供的 `test/shinya.apk`（1.2.4 / 183249835）制作，包名为
`com.labprobe.shinyahub`，当前版本为 `1.2.4-hub.2`。独立签名，可以与原版并存。

## 本轮范围

- 账号密码页改为 **Hub 地址 / APP Token**，隐藏短信、微信及官方账号入口。
- 原业务请求经 `HubInterceptor` 发往所选 Hub；未配置时返回登录提示。
- 隐藏推广、商城、客服、问卷、官方更新提示及本轮未接入的入口。
- 保留原有设备温度、终端名称和设备图标相关界面；设备数据使用 MAC 标识。
- 第一阶段接入登录、项目列表、路由器信息和终端列表。

无线设置等固件写操作尚未接入，服务端会返回明确的“尚未接入”提示。
独立 APK 的手机启动、登录、首页呈现与图标修改仍需实机验证。
这不是所有固件功能均已完成的正式版。

## 使用

安装 `test/output/shinya-hub-1.2.4-hub.2.apk`，可覆盖 hub.1。输入可访问的 Hub 地址和现有 APP Token。
BE72 所在局域网的 Hub 地址为 `http://192.168.5.46:58443`。
外网使用自己当前可访问的 Hub 地址；路由器 EWEB 地址不是 Hub 登录地址。
多路由器部署可填写对应的 `/r/<router>` 地址前缀，但对应 worker 也须部署适配模块。

## 源文件与复现

- `HubInterceptor.java`：原 React Native OkHttp 请求的地址适配。
- `patch_bundle.py`：对指定 SHA256 的 Hermes v90 bundle 做有限修改，检查未修改字符串及函数的字节一致性。
- `patch_apk.py`：独立包名、显示名称、原生请求接入及统计上报停用。
- `SignApk.java` / `sign.py`：独立签名并核验 v1/v2/v3 签名。

构建使用 apktool 3.0.3、JDK 17、D8/R8 8.13.17、apksig 9.2.0。
分析及构建中间文件在 `test/_analysis/oct08-shinya-hub`，工具在 `test/_tools`。
流程为 apktool 解包 → 修改 Hermes bundle → 编译 interceptor、D8、smali →
应用 `patch_apk.py` → apktool 重建 → `sign.py` 签名。

签名密钥保存在被 Git 忽略的 `test/_keys/shinya-hub`，不提交到源码库。
后续升级沿用该密钥；若更换密钥，Android 无法直接更新此测试版。

## 2026-10-08 启动闪退修复

应用宝 Android 13 模拟器的日志确认 hub.1 在登录页渲染时崩溃：
`accessibilityState.disabled` 收到了数字，原生组件要求布尔值，错误为
`Value for disabled cannot be cast from Double to Boolean`。

原因是 `patch_bundle.py` 为 `useSignIn` 的 loading 初始状态传入了寄存器 r4，
其中存放的是数组下标数字 `0`。hub.2 保留密码模式及同意状态为 `true`，
为 loading 单独载入布尔值 `false`，函数长度及其他函数偏移保持不变。
版本号提升至 `183249837`，沿用 hub.1 的签名。

日志保存在 `test/_analysis/oct08-shinya-hub/startup-before-fix.log`。
hub.2 已在用户提供的应用宝 Android 13 / x86_64 模拟器上覆盖安装并验证：
登录页正常显示，进程持续运行，未产生新的崩溃日志。截图为
`test/_analysis/oct08-shinya-hub/startup-hub2.png`。
应用宝需通过宿主启动应用；直接使用 ADB 启动会被其进程策略拦截。
本次验证范围为启动及登录页显示；Hub 登录、设备数据及真实手机尚未验证。
修复包的实际启动验证状态以输出目录中的 `.verification.json` 为准；
签名验证与字节码静态检查不能替代实际启动验证。

## 服务端

`D:/Github/labprobe-hub/shinya_compat.py` 适配原协议，并校验 APP Token、项目 ID 及设备 SN。
`tests/test_shinya_compat.py` 覆盖鉴权、真实数据映射、路由器隔离、离线设备及未接入写操作。
部署脚本 `scripts/deploy_shinya_compat.py` 仅添加适配模块及启动入口，备份后重载 Hub。
2026-10-08 已部署到 BE72 的 `labprobe-hub` 容器，未部署到 BE50 worker。

