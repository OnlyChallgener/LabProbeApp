# LabProbe App

极客网探 Android 客户端，使用 Kotlin + Jetpack Compose。仓库长期只维护 `main` 主线；历史版本通过 Git Tag 与 GitHub Release 留档，不再为每个 build 创建长期 release 分支、一次性 workflow 或独立版本说明文件。

## 项目组成

LabProbe 由三部分协作：

- **LabProbe App**：Android 客户端，负责网络状态、终端、路由器控制、诊断、收藏访问等交互。
- **LabProbe Hub**：中心服务，负责状态聚合、同步、更新与远程访问接口。
- **LabRelay**：运行在适配路由器上的采集/控制代理，通过 `HOOK_TOKEN` 与 Hub 通信。

## 数据连接

APP 的数据连接采用“首次全量 + 后续增量 + 定期校准”：

- 首次进入、重新登录、Hub 重连、恢复前台和网络切换时获取完整快照。
- 普通自动刷新只处理 Hub `revision/sequence` 增量变化。
- 每 5 分钟完整校准；序号中断时立即回退完整校准。
- 无变化时静默刷新，不替换列表、不清空卡片、不造成整页闪动。
- 校准失败时保留旧页面和最后更新时间。
- Hub 旧版本不支持同步接口时，自动回退原状态、设备和事件接口。

Hub 连接监督与数据刷新分离：已连接时刷新只同步数据；断开后显示断开状态并自动重连，失败后提示手动测试或刷新。

## Token 与安全

Hub 使用两个用途独立的令牌：

- `APP_TOKEN`：APP 访问管理、状态和同步 API。
- `HOOK_TOKEN`：LabRelay、Lucky 和 Webhook 上报或读取路由器接口。

APP 设置页只填写 Hub 地址与 `APP_TOKEN`，并由 Android Keystore 加密保存。`HOOK_TOKEN` 不填写到 APP。

部署时另外为 MQTT 设置独立强密码。`APP_TOKEN`、`HOOK_TOKEN`、`MQTT_PASSWORD` 不要相同，也不要使用示例占位值。

## 本地构建

要求：JDK 17、Android SDK 36、Build Tools 36.0.0、Gradle 9.5.1。

```bash
gradle :app:testDebugUnitTest :app:assembleRelease --stacktrace
```

版本号唯一来源：

```text
app/build.gradle.kts
```

正式构建必须读取其中的：

```kotlin
versionCode = <递增整数>
versionName = "<major.minor.patch>"
```

## 固定签名

Android 覆盖安装要求 **包名相同 + 签名证书相同 + versionCode 不降低**。固定签名密钥必须长期保存，不能每次生成新的 key。

本地可复制模板：

```bash
cp signing.properties.example signing.properties
```

然后配置：

```properties
LABPROBE_KEYSTORE_PATH=keystore/labprobe-upload.jks
LABPROBE_KEYSTORE_PASSWORD=你的keystore密码
LABPROBE_KEY_ALIAS=labprobe
LABPROBE_KEY_PASSWORD=你的key密码
```

GitHub Actions 使用以下 Repository Secrets：

```text
LABPROBE_KEYSTORE_BASE64
LABPROBE_KEYSTORE_PASSWORD
LABPROBE_KEY_ALIAS
LABPROBE_KEY_PASSWORD
```

`.gitignore` 必须继续排除：

```text
signing.properties
*.jks
*.keystore
keystore/
```

密钥丢失后将无法继续覆盖安装使用旧签名的 APK，因此需要自行安全备份。

## Hub 与 LabRelay 部署

推荐顺序：**Hub → APP → LabRelay**。

### 1. Hub

在 Docker/NAS 上部署 `labprobe-hub` 与 MQTT 服务。至少配置：

```text
APP_TOKEN
HOOK_TOKEN
MQTT_USERNAME
MQTT_PASSWORD
HUB_ADVERTISE_URL
MQTT_PUBLIC_URL
```

可用 OpenSSL 生成两条独立 Token：

```sh
printf 'APP_TOKEN='; openssl rand -hex 32
printf 'HOOK_TOKEN='; openssl rand -hex 32
```

Hub 默认服务端口按部署配置使用；APP 与 LabRelay 必须连接到同一套 Hub 配置。

### 2. APP

在“我的 / 设置”中填写：

```text
Hub 地址
APP_TOKEN
```

保存后立即校准。

### 3. LabRelay

SSH 登录已适配路由器后安装：

```sh
wget -O /tmp/labprobe-install.sh https://lab.net86.dynv6.net:27772/agent/install.sh \
&& sh /tmp/labprobe-install.sh
```

安装时填写与 Hub 一致的 `HOOK_TOKEN`。完成后可检查：

```sh
labrelay status
labrelay test-hub --config /etc/labprobe/agent.json
tail -f /tmp/labprobe/labrelay-agent.log
```

## 版本号规则

版本采用两套数字，各司其职：

- **versionCode**：Android 内部构建号，每次正式发布必须严格 `+1`，永不回退、永不复用。
- **versionName**：语义版本 `major.minor.patch`。

建议规则：

- Bug 修复、UI 调整、小优化：`patch + 1`
- 明显新增功能或模块升级：`minor + 1`，同时 `patch` 归零
- 大架构或不兼容升级：`major + 1`，同时 `minor/patch` 归零

每一个正式 GitHub Release 都必须拥有唯一的 `versionName + versionCode` 组合，不再发布多个 build 共用同一个 `versionName`。

## CI 与 Release

仓库长期只保留两个 Workflow：

```text
CI
Android Release
```

### CI

触发条件：

- push 到 `main`
- Pull Request 面向 `main`

职责：

- 检查仓库结构与版本格式
- 禁止新增一次性 workflow
- 禁止 workflow 自动修改并 push 源码
- 运行单元测试
- 编译 Release APK

CI **不发布版本**。

### Android Release

触发方式：

1. `workflow_dispatch`：只进行签名构建和验证，不发布 GitHub Release。
2. 推送符合格式的 Git Tag：进行签名构建、验证并发布 GitHub Release。

Tag 格式固定为：

```text
v<versionName>-build<versionCode>
```

例如源码是：

```kotlin
versionCode = 200
versionName = "0.10.45"
```

对应 Tag：

```text
v0.10.45-build200
```

Release Workflow 会验证：

- Tag 中的版本与 `app/build.gradle.kts` 完全一致。
- 发布提交就是当时 `main` 的 HEAD。
- 单元测试与 Release 编译通过。
- APK 文件有效。
- APK 内版本与源码一致。
- APK 固定签名校验通过。
- 输出 SHA256。

## 标准发版流程

以后固定按下面流程执行：

```text
开发 / 修复
  ↓
PR → main
  ↓
CI 通过
  ↓
准备正式版本时，只修改 app/build.gradle.kts 的 versionCode / versionName
  ↓
PR → main
  ↓
main CI 通过
  ↓
创建 Tag：v<versionName>-build<versionCode>
  ↓
Android Release 自动构建、签名、校验
  ↓
GitHub Release + signed APK
```

禁止重新采用以下模式：

```text
release/buildXXX 长期分支
one-off-*.yml
apply-build*.yml
finalize-build*.yml
Workflow 修改 Kotlin/Gradle 后自动 git push
每个版本新建一个 Markdown 文件
```

## 更新检测

APP 保留原更新入口、弹窗、忽略更新、下载目录和安装流程：

- 主源：Lucky 更新仓 `UpdateRepository.APP_MANIFEST`
- 备用源：GitHub Release API
- 主源超时、请求失败或 JSON 无效时才切换 GitHub
- 只有远端 `versionCode` 高于当前版本才提示升级
- 存在 `sha256` 时，下载完成后必须校验通过才允许安装

需要生成统一更新包时，使用 Hub 仓库的 `scripts/build_update_bundle.py`，版本参数必须读取本次正式 APK 与 LabRelay 的实际版本，不要在本仓库 README 中写死具体发布版本。

## 仓库维护约定

- 长期开发主线只使用 `main`。
- 历史版本由 Git Tag / GitHub Release 保存。
- 根目录文档只维护本 `README.md`。
- 新版本说明写入 GitHub Release Notes，不再维护独立 `CHANGELOG.md`。
- 签名说明、部署说明、版本规则、Release 规则统一维护在本文件。
- 根目录 `labprobe_icon_1024.png` 作为 Logo 原始资源暂时保留；若确认无设计留档价值，可后续单独删除。
