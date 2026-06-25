# LabProbeApp v0.9.0

Kotlin + Jetpack Compose + Material 3 的家庭网络仪表盘。

## v0.9.0 重点

- 基于 v0.8.9 累计修复。
- Ping 延迟曲线标题与丢包胶囊同一行显示。
- Ping 图表按 1 秒聚合展示，避免 30ms 采样时折线过密。
- 丢包点缩小，并按秒聚合显示，不再串成红线。
- 参数区继续收敛：小框和按钮更紧凑，但文字保持垂直居中。
- 离线设备信息继续保留到下一次上线。

## 构建

GitHub Actions: Android APK → Run workflow

本地:

```bash
gradle :app:assembleDebug
```
