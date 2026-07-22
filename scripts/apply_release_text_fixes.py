#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"
STATUS = ROOT / "app/src/main/kotlin/com/labprobe/app/RouterStatus.kt"


def apply() -> None:
    main = MAIN.read_text(encoding="utf-8")
    old = '''    val CHANGELOG = listOf(
        "v0.10.6 build134 · 首页关注与概览显示修复" to listOf(
            "首页关注终端只显示当前仍在关注列表中的设备",
            "移出关注后首页立即同步隐藏，不再残留",
            "今日概览底部同步说明支持横向滑动查看完整内容"
        )
    )'''
    new = '''    val CHANGELOG = listOf(
        "v0.10.12 build142 · 路由器原生状态与设置重构" to listOf(
            "路由器状态接入 eWeb WebSocket，实时显示 CPU、内存、温度、运行时间、流量和连接数",
            "大 VPN 卡改为路由设置，路由器配置功能集中到独立二级页面",
            "修复 DDNS 卡片与更多菜单闪退，异常状态不再整卡标红",
            "网络自检改为手动触发说明，合并重复网线结果并补充中文显示"
        )
    )'''
    # Fresh checkout starts at the old block. A workflow may run source
    # preparation explicitly and Gradle preBuild may run it again, so newer
    # generated changelogs must be accepted without raising.
    already_generated = (
        new in main
        or "v0.10.13 build143 · 路由诊断与首页联动" in main
    )
    if not already_generated:
        if old not in main:
            raise RuntimeError("missing changelog block")
        main = main.replace(old, new, 1)
        MAIN.write_text(main, encoding="utf-8")

    status = STATUS.read_text(encoding="utf-8")
    status = status.replace("实时数据稍旧，等待 Agent 更新", "实时数据稍旧，等待 Hub WebSocket 更新")
    STATUS.write_text(status, encoding="utf-8")


if __name__ == "__main__":
    apply()
    print("release text fixes applied")
