# LabProbe v0.9.17 build98 · main full overwrite roaming safe entry

## 修复

- 基于用户当前 main 完整覆盖包制作。
- 漫游测试页进入时不再自动拉起系统权限页。
- 移除漫游页 ActivityResult 权限启动路径，改为点击“开始测试”时通过 ActivityCompat 请求权限，避免部分国产系统把 APP 切到桌面。
- 保留未连接 Wi-Fi 时的开始前拦截提示。
- 保留 build91~build93 的漫游报告、小卡片、Mini Device Icons、设备/WOL改动。

## 说明

如果仍然返回桌面，请确认手机安装的 APK versionCode 为 98，并抓取 AndroidRuntime / FATAL EXCEPTION 日志。
