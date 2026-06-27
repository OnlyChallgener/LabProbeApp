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
- versionCode: 47


## v0.9.15-hotfix1
- 修复 Kotlin 字符串插值：真实采样率文本使用 `${rate}`，避免 `rate次` 被识别为变量。
- 修复 OkHttp Dns 固定地址解析：改为 `object : Dns`，避免接口构造错误。
- versionCode 更新至 46，便于 GitHub Actions 重新打包。


## v0.9.15-hotfix2
- 延迟测试页面标题再缩小一号，卡片标题更轻，减少拥挤。
- 图表卡片标题改为“延迟”，去掉省略号和 X/Y 轴小字备注。
- Y 轴最多显示 5 个点位；低延迟场景固定展示 0 / 30 / 60 / 90 / 120。
- 延迟图表高度增加到 222dp，最高点位更靠上，显示更舒展。
- 停止按钮启用态改为科技蓝，不再使用墨绿色。
- ICMP 高频采样增加取消时进程强回收，并为 IPv6 增加 ping6 到 ping -6 的回退。
- versionCode 更新至 47，便于 GitHub Actions 重新打包。
