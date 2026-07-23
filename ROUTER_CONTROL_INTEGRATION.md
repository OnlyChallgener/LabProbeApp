# LabProbe Router Control v0.10.11 / build 141

目标分支：`codex/router-control-v01011-build141`

## 已完成

- 工具箱新增一行可横向滑动的路由器功能小卡片
- 映射与UPnP三分页：`IPv6映射 / 端口映射 / UPnP`
- DDNS与证书监控合并
- 防火墙、网络自检、路由器连接设置
- 原终端卡片增加实时上传、实时下载、连接数
- 商业级紧凑卡片、自绘Canvas小图标、手机底部编辑弹层
- APP `0.10.11` / build `141`
- 配套 Hub `0.9.8`、LabRelay `0.2.9`

## 原页面保持原则

- 路由器状态页保持原布局，仍调用原 `/api/router/dashboard`；Hub已在内部改为直接RPC同步。
- 终端列表保持原布局和识别逻辑，只增加一条紧凑实时状态栏。
- 原IPv6映射页复用旧 `PortMappingScreen`，不重构原业务。
- 原工具箱卡片、分组、排序和其他页面不改。

## 工具箱入口

```text
路由器功能                         ⚙
[映射与UPnP] [DDNS] [防火墙] [网络自检]
```

右侧小齿轮进入“路由器连接”。

## 路由器密码流程

用户在APP中输入：

```text
管理地址
管理密码
会话超时 600-7200秒
```

```text
APP → Hub保存并测试登录 → Hub后续自动维护sid/stok
```

已保存密码时，编辑页面留空表示保持原密码。用户不需要接触密文或解密流程。

## 页面行为

- **IPv6映射**：保留原样。
- **端口映射**：路由器原生IPv4 NAT；新增/编辑使用底部大弹出页；删除使用中部确认框。
- **UPnP**：总开关、默认线路、只读动态映射。
- **DDNS**：紧凑全屏编辑；与证书监控使用两个页签。
- **防火墙**：转发/入站/出站，IPv4/IPv6/双栈，TCP/UDP/ICMP/ANY，允许/丢弃，启停、编辑、删除。
- **网络自检**：每秒同步，100%后停止。
- **写操作**：Hub写入 → 再GET验证 → APP更新。

## 终端实时状态

```text
实时 ↑120 Kbps ↓1.26 Mbps · 连接 26
```

Hub返回兼容字段：

```text
realtimeUpload / realtimeUploadBytes
realtimeDownload / realtimeDownloadBytes
connectionCount
```

终端IPv6继续由LabRelay邻居数据按MAC合并。

## 拉取分支

APP：

```bash
cd /d D:\Github\LabProbeApp
git fetch origin
git checkout codex/router-control-v01011-build141
git pull origin codex/router-control-v01011-build141
```

Hub / LabRelay：

```bash
cd /d D:\Github\labprobe-hub
git fetch origin
git checkout codex/router-rpc-v098-relay-v029
git pull origin codex/router-rpc-v098-relay-v029
```

## 最后一次源码规范化

GitHub网页进入APP仓库：

```text
Actions → Router Control Auto Integrate → Run workflow
```

它会清理重复导入/图标并提交到当前APP分支。此步骤可重复执行，脚本是幂等的。

## 构建APP

本地：

```bash
python scripts/integrate_router_control.py
python scripts/cleanup_router_control.py
gradle :app:assembleRelease --stacktrace
```

APK：

```text
app/build/outputs/apk/release/app-release.apk
```

GitHub Actions：

```text
Actions → Android Release APK → Run workflow
```

首次看到“Approve and run”时先批准工作流。成功后下载：

```text
LabProbe-v0.10.11-build141-release-apk
```
