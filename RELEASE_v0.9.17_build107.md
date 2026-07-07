# LabProbe v0.9.17 build107 · roaming single-page summary

本版基于 build101 稳定漫游入口继续小步恢复功能，避免再次引入三级页面和 Wi‑Fi 扫描首帧风险。

## 修复与优化

- 保留 build101 稳定策略：进入无线漫游页不读 Wi‑Fi、不弹权限、不扫 AP。
- 速率行改为横向滑动，长内容不再省略截断。
- 新增当前测试总结折叠卡片，只在无线漫游当前页面展示，不进入三级页面。
- 恢复漫游质量评分卡片。
- 继续保留 RSSI / BSSID / 延迟 / 丢包 / AP 切换事件链。
- 不启用候选 AP 扫描和 Sticky Score，避免触发不稳定底层 Wi‑Fi scanResults。

## 说明

历史二级页面、报告保存和候选 AP 扫描暂不恢复。后续建议做成 BottomSheet 且点击后懒加载，不再做三级页面。
