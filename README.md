# Labprobe v0.9.15

极客网探 Labprobe Android 客户端，Kotlin + Jetpack Compose 单文件工程。

## v0.9.15 本次重点

- 延迟测试从单一 Ping 升级为 ICMP / TCP Connect / HTTP HEAD / HTTP GET。
- 支持 IPv6 优先、IPv4 优先、仅 IPv6、仅 IPv4，以及 DNS A/AAAA 优先策略。
- Ping/延迟测试页面按 One UI 风格重做参数区：科技蓝小图标、标题缩小、卡片高度压缩。
- 曲线 X 轴使用真实耗时；图表 1 秒聚合展示，原始数据实时采集。
- Y 轴最小刻度为 30ms，尖峰与丢包单独标记。
- 图表右上角显示真实采样率，避免只看设置间隔误判。
- 历史记录弹窗保存最近 10 次测试汇总，显示占用空间，可折叠查看。
- 工具页卡片支持整张点击进入。

## GitHub Actions

上传到 GitHub 后，Actions 会构建 debug APK：

```bash
gradle :app:assembleDebug --stacktrace
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 版本

- versionName: 0.9.15
- versionCode: 45
