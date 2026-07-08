# LabProbe v0.9.17 build115 - Roaming Stable Rollback

本版本用于撤回 build114 中导致无线漫游退回桌面的变更。

## 变更
- 基于 build113 稳定源码恢复无线漫游入口与测试逻辑。
- versionCode 提升到 115，允许直接覆盖安装 build114/build113。
- 暂不保留 build114 的手动 Wi-Fi 切换事件改动，避免再次触发退桌面问题。
- 保留 build113 的淡蓝白背景、状态栏融合、漫游小卡修复方向。

## 验证重点
- 可以覆盖安装高于 build114 的版本。
- 点击无线漫游卡片不退回桌面。
- 开始测试正常。
