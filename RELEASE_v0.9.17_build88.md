# LabProbe v0.9.17 build88 - Interaction Refactor

本版聚焦 APP 操作逻辑收口，不改 Hub / Router Agent / API。

## 主要变更

- 新增轻量 Design Components：LabCard / LabSection / LabInfoRow / LabBottomSheet / LabStatusBadge / LabIconBox。
- 设备卡片支持点击进入设备详情 BottomSheet。
- 设备详情改为 Section 风格：网络、无线、设备、能力。
- 设备编辑改为 BottomSheet，不再使用小弹窗。
- WOL 添加/编辑改为 BottomSheet。
- 设备类型规则修正：绿联不再仅凭品牌识别 NAS，仅根据 NAS 型号识别。
- 绿联 NAS 型号库加入 DH / DXP 系列：DH2100+、DH2600、DH2300、DH4300Plus、DX4600、DXP4800、DXP6800、DXP8800 等。
- 降低卡片阴影，减少上次 UI 重构导致的滑动压力。

## 设计原则

- 一级页面看状态。
- 设备详情用 Section 展示。
- 编辑和危险操作收敛到底部弹层。
- 不在列表项里加入复杂 Canvas / Blur / 无限动画。

## 版本

- versionName: 0.9.16
- versionCode: 88
