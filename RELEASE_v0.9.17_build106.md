# LabProbe v0.9.17 build106 · roaming stable reports

本版基于 build104 稳定源码树恢复无线漫游的总结和历史功能，但默认不启用周边 AP 扫描，避免再次触发底层 Wi-Fi API 导致退回桌面。

## 变更

- 无线漫游入口保持稳定：进入页面不读 Wi-Fi、不扫 AP、不弹权限。
- 测试时仅采集当前连接 Wi-Fi、RSSI、BSSID、链路速率、Ping 延迟和丢包。
- 恢复测试总结二级页面。
- 恢复漫游历史二级页面，支持折叠、删除、清空、最多 20 条、显示占用 KB。
- 恢复保存报告。
- 恢复漫游事件链和质量评分。
- 速率行支持横向滑动，不再截断 Tx / Rx 长信息。

## 暂不启用

- 候选 AP 扫描 / scanResults 默认关闭。
- Sticky Score 粘 AP 暂显示为未启用。

后续版本再通过高级开关单独启用候选 AP 扫描，并做全量 runCatching 保护。
