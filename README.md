# 极客网探 / Labprobe v0.9.15

One UI 交互顺滑版。可直接上传 GitHub，GitHub Actions 会构建 debug APK。

## v0.9.15 更新重点

- 首页「今日概览」优先读取 Hub 的 `/api/daily?date=yyyy-MM-dd`，和「记录 - 每日总结」保持同一天数据同步；接口不可用时才使用本地事件缓存兜底。
- 首页卡片支持整张卡直接进入：终端、VPN/STUN、出口与路由、今日概览不需要再点小区域。
- 首页长按拖动排序改成先浮起、跟手移动，松手后再完成排序，避免拖动中突然跳位。
- 记录页左滑删除增加回弹动画和删除消隐动画，不再一闪就没。
- 设置页输入框保持白色底，减少灰底感。
- 版本号统一为 v0.9.15，versionCode = 43。

## GitHub 上传

1. 解压本包。
2. 上传全部文件到 GitHub 仓库根目录。
3. Actions 里运行 `Android APK`。
4. 构建完成后，在 Artifacts 下载 `JiKeWangTan-debug-apk`。

Hub 继续使用 v0.7.2，不需要更新 Hub。
