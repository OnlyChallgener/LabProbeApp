# LabProbe App

极客网探 Android 客户端，Kotlin + Jetpack Compose。当前开发版为 `v0.10.2 build130`。

第三方首次部署请直接查看：[Hub 与 LabRelay 简明安装](THIRD_PARTY_INSTALL.md)。新用户使用一次性配对码，不需要预先创建 `APP_TOKEN` 或 `HOOK_TOKEN`。

## 数据连接

APP 保持现有页面、卡片、排序和跳转逻辑，数据连接采用“首次全量 + 后续增量 + 定期校准”：

- 首次进入、重新登录、Hub 重连、恢复前台和网络切换时获取完整快照。
- 普通自动刷新只处理 Hub `revision/sequence` 增量变化。
- 每 5 分钟完整校准；序号中断时立即回退完整校准。
- 无变化时静默刷新，不替换列表、不清空卡片、不造成整页闪动。
- 校准失败时保留旧页面和最后更新时间。
- Hub 旧版本不支持同步接口时，自动回退原状态、设备和事件接口。

Hub 连接监督与数据刷新分离：已连接时刷新只同步数据；断开后显示断开状态，并自动尝试重连 5 次，失败后提示手动测试或刷新。

## 配对

新 Hub 首启会在容器日志显示一次性 APP 配对码。APP 设置页结构保持不变：

- Hub 填写 `http://192.168.x.x:58443` 或 `https://hub.example.com`
- Token 输入框填写 `APP-123456`

连接时 APP 自动换取独立 Client Token，并保存到 Android Keystore。旧 `APP_TOKEN` 继续兼容。

## 更新检测

APP 保留原入口、弹窗、忽略更新、下载目录和安装流程。

- 主源：Lucky 更新仓 `UpdateRepository.APP_MANIFEST`
- 备用源：GitHub Release API
- 只有主源超时、请求失败或 JSON 无效时才切换 GitHub
- `versionCode` 高于当前版本才提示更新
- `versionCode` 低于当前版本时提示“更新仓版本低于当前 APP”
- `sha256`、`sizeBytes`、`fallbackUrl` 向后兼容，可缺省
- 存在 `sha256` 时下载完成后必须校验，通过后才允许安装

## 构建

本地构建命令：

```bash
gradle :app:assembleRelease --stacktrace
```

GitHub Actions 会生成 Release APK。固定签名仍使用仓库既有 Secrets/签名配置。

## 发版文件

发版时使用 Hub 仓库的 `scripts/build_update_bundle.py` 生成统一更新包：

```powershell
cd D:\Github\labprobe-hub
python scripts\build_update_bundle.py `
  --app-apk D:\Release\app-release.apk `
  --app-version-name 0.10.2 `
  --app-version-code 130 `
  --agent-arm64 D:\Release\labrelay-linux-arm64 `
  --agent-version 0.2.1 `
  --output D:\Release\update-bundle
```

需要上传到更新仓的 APP 文件：

- `app/update.json`
- `app/LabProbeApp-v0.10.2.apk`
