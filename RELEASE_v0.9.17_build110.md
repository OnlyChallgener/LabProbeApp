# LabProbe v0.9.17 build110 · roaming candidate AP scan

## 变更

- 在无线漫游配置区新增“候选 AP 扫描”开关。
- 默认关闭，不进入页面读取 scanResults，保持 build109 的稳定入口。
- 开启后仅在测试运行中读取 WifiManager.scanResults，用于候选 AP 数量、候选 BSSID、RSSI 差值与粘 AP 判断。
- scanResults / startScan 全部 runCatching 包裹，失败时显示候选 AP 不可用，不影响基础漫游测试。
- 实时指标条新增候选 AP / 差值指标。
- 速率行继续保持横向滑动。

## 验证重点

1. 进入无线漫游页不退桌面。
2. 候选 AP 扫描关闭时可正常测试。
3. 开启候选 AP 扫描后，候选 AP / 差值 / 粘 AP 数据应出现；若系统限制扫描，不应闪退。
