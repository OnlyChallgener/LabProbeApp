# LabProbe 全系统安装与升级完整指南 (App / Hub / Relay)

本文档提供 **LabProbe** 极客网探全套三端架构（**LabProbe App**、**LabProbe Hub**、**LabRelay**）的最新环境要求、全新部署、配置连接、平滑升级与常见故障排查说明。

---

## 协同版本基线

为保证各项特性（15 分钟平滑流量趋势、STUN 穿透与防火墙联动、WireGuard 多模式漫游、AI 对话运维等）正常协同，建议各组件版本保持在以下基线或更新：

| 组件名称 | 推荐版本 | 运行平台 | 核心职责 |
| :--- | :---: | :---: | :--- |
| **LabProbe App** | `v0.12.0+` (build 241+) | Android 10+ (手机/平板) | 状态呈现、网络工具箱、路由控制、穿透与诊断 |
| **LabProbe Hub** | `v0.11.x+` (最新主线) | Linux AMD64 / ARM64 (Docker) | 数据聚合、状态持久化 (SQLite)、WSS/REST 接口、更新仓 |
| **LabRelay** | `v0.2.45+` | Linux aarch64 (适配路由器 / OpenWrt) | 硬件遥测、TCP 穿透与压测、NDP 邻居与接口数据采集 |

---

## 一、LabProbe Hub（中心端）全新安装与升级

Hub 作为家庭网络的控制枢纽，建议部署在常开的软路由、NAS、迷你主机或云服务器上（支持 Docker / Docker Compose）。

### 1. 全新安装步骤

#### (1) 克隆仓库与准备目录
```sh
git clone https://github.com/OnlyChallgener/labprobe-hub.git
cd labprobe-hub

# 创建运行持久化目录
mkdir -p data config backups logs
```

#### (2) 配置环境变量 `.env`
复制配置模板：
```sh
cp .env.example .env
cp config.example.yaml config/config.yaml
```
编辑 `.env` 文件，关键配置项说明：
```dotenv
HUB_NAME=LabProbe Hub
HUB_ADVERTISE_URL=http://192.168.1.20:58443
HUB_HOST_IPV4=192.168.1.20
HUB_HOST_IPV6=

# 关键安全令牌（两项务必独立生成长随机字符，绝不可相同）
APP_TOKEN=请改为长随机令牌_仅用于App客户端登录
HOOK_TOKEN=请改为另一条长随机令牌_用于路由器Relay上报

# MQTT 配置（按需）
MQTT_USERNAME=labprobe
MQTT_PASSWORD=请设置强随机密码
```

#### (3) 启动容器服务
- **模式 A：Host 网络模式（强烈推荐，适合局域网内部署，支持局域网广播 WOL）**：
  ```sh
  docker compose -f docker-compose.host.yml up -d --build
  ```
- **模式 B：Bridge 桥接网络模式（适合云服务器或端口映射部署）**：
  ```sh
  docker compose -f docker-compose.bridge.yml up -d --build
  ```

#### (4) 验证运行状态
访问健康检查地址：
```sh
curl http://127.0.0.1:58443/health
# 正常返回: {"ok": true, "status": "healthy"}
```

---

### 2. Hub 平滑升级步骤

Hub 采用 SQLite 保存持久化状态，并在启动事务中执行数据库版本迁移与 `integrity_check`，升级过程保证数据零丢失。

```sh
cd labprobe-hub

# 1. 拉取仓库最新代码
git pull origin main

# 2. 拉取最新镜像并平滑重建容器
# 若使用 Host 模式：
docker compose -f docker-compose.host.yml pull
docker compose -f docker-compose.host.yml up -d

# 若使用 Bridge 模式：
docker compose -f docker-compose.bridge.yml pull
docker compose -f docker-compose.bridge.yml up -d

# 3. 检查升级后运行日志
docker logs --tail 50 -f labprobe-hub
```

---

## 二、LabRelay（路由器代理端）全新安装与升级

LabRelay 是针对已适配的路由器（如锐捷 ReyeeOS、OpenWrt 等 aarch64 平台）编译的纯 Rust 二进制程序，内置 `daemon`（中继转发与测速）和 `agent`（状态采集与上报）。

### 1. 全新一键安装

通过 SSH 连接到路由器终端，执行通用极速安装脚本：

```sh
# 格式：sh install.sh <HUB_URL> <HOOK_TOKEN> [ROUTER_NAME]
wget -O /tmp/install.sh http://<你的Hub地址>:58443/agent/install.sh
sh /tmp/install.sh http://<你的Hub地址>:58443 YOUR_HOOK_TOKEN router
```

**安装脚本会自动完成：**
1. 自动适配并下载对应的 aarch64 二进制文件到 `/usr/bin/labrelay`；
2. 写入配置到 `/etc/labprobe/agent.json`；
3. 注册 OpenWrt `procd` 系统自启守护脚本 `/etc/init.d/labprobe`；
4. 启动服务并自动执行 `labrelay test-hub` 验证通信。

---

### 2. Relay 升级步骤

#### 方法 A：命令行平滑热升级（推荐）
在路由器 SSH 中执行单行升级命令，自动拉取最新二进制并重启守护进程，保留现有配置：
```sh
sh /etc/labprobe/install.sh upgrade
```

#### 方法 B：通过 Android App 远程触发静默升级
在手机 App 的“路由器状态”或“设置”中，若检测到 Hub 发布了新的 Relay Bundle，可一键点击静默升级，Agent 将在后台完成热替换并上报完成状态。

---

### 3. Relay 常用运维与自检命令

在路由器 SSH 终端中可随时执行以下指令排查：

```sh
# 1. 测试与 Hub 的通信连通性与 HOOK_TOKEN 鉴权
labrelay test-hub

# 2. 查看当前采集状态与上报快照
labrelay status

# 3. 综合自检（网络接口、Socket 与中继连通性）
labrelay doctor

# 4. 查看系统服务运行日志
logread | grep -i labrelay
```

---

## 三、LabProbe App（Android 客户端）安装与升级

客户端基于 Kotlin + Jetpack Compose 构建，界面现代轻盈。

### 1. 全新安装

1. 从官方 GitHub Releases 页面下载最新发布的正式 APK：
   👉 [LabProbeApp Releases](https://github.com/OnlyChallgener/LabProbeApp/releases)
2. 在 Android 手机上安装 APK（需允许安装未知应用来源权限）。
3. **初次配对配置**：
   - 打开 App，首次进入会自动引导或点击进入“设置”；
   - **Hub 地址**：输入 Hub 的局域网或公网地址（例如 `http://192.168.1.20:58443` 或 `https://hub.yourdomain.com`）；
   - **登录令牌**：填写在 Hub `.env` 中配置的 `APP_TOKEN`（App 会通过 Android Keystore 硬件加密存储，请勿填写 `HOOK_TOKEN`）；
   - 点击“测试并保存”，连接成功后主页卡片即刻呈现！

---

### 2. App 覆盖升级规范

- **覆盖安装核心原则**：
  Android 系统要求覆盖升级必须满足：**相同包名 + 相同签名证书 + versionCode 递增**。
- **升级渠道**：
  - **手动下载覆盖**：直接在 GitHub Releases 页面下载最新 APK 点击覆盖安装即可，所有本地 Profile、缓存与登录状态均自动完整保留；
  - **App 内在线检测升级**：在 App 中进入“设置 / 工具箱” ➜ “Beta 在线升级”，即可一键拉取最新 Release 镜像检查更新。

---

## 四、三端协同故障速查表 (FAQ)

### Q1: App 提示“Hub 连接断开”或“502 / 网关错误”？
1. 检查 Hub 容器是否正常运行：`docker ps | grep labprobe`；
2. 检查手机所处网络是否能 ping 通 Hub 的 IP，或反向代理域名证书是否有效；
3. 检查 App 中输入的令牌是否为 **`APP_TOKEN`**（常见误区：误填成了路由器的 `HOOK_TOKEN`）。

### Q2: 路由器 Relay 提示 `Authentication Failed (401/403)`？
1. 查看路由器当前配置文件：`cat /etc/labprobe/agent.json`；
2. 确认 `hookToken` 字段是否与 Hub 机器上 `.env` 中的 `HOOK_TOKEN` 严格一致；
3. 在路由器执行 `labrelay configure --hub <HUB_URL> --hook-token <TOKEN> --name router` 重新写入后重试 `labrelay test-hub`。

### Q3: 运营商宽带重新拨号导致 IPv6 前缀变化后，STUN 或 DDNS 地址不同步？
- LabProbe 具备动态前缀感知能力：路由器 WAN 口获取新前缀后，Relay 会在下一次扫描事件中探测到 GUA 前缀变化；
- Hub 会自动将旧前缀地址打上 `historical` 标签并重新打分，选出新前缀下的首选地址（Primary IPv6）；
- 手机 App 端的 WireGuard STUN 模式会自动通过更新的 `公网 IP:端口` 刷新连接，无需人工干预。
