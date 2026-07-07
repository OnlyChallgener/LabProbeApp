# LabProbe v0.9.17 build100 · 漫游权限编译修复

## 修复

- 补齐 `safeHasWifiRoamingPermissions` / `safeMissingWifiRoamingPermissions`。
- 补齐 `Context.findActivity()`，用于安全请求运行时权限。
- 补充 `ActivityCompat` 引用，修复 GitHub Actions `compileReleaseKotlin` 失败。
- 保持漫游页进入时不自动弹权限；点击开始测试时才检查并请求权限。
- versionCode 更新到 100。
