# LabProbe v0.9.17 build96 · Compose buildfix

修复 build95 构建失败：

- 移除 `WifiRoamingTool()` 外层 `try/catch`，因为 Compose 不支持在 `try/catch` 中包裹 composable 调用。
- 保留漫游入口延迟初始化逻辑。
- versionCode 更新到 96。

不改 Hub / Router Agent / API。
