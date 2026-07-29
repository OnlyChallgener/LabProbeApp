#!/usr/bin/env python3
"""Guarantee that build165 user-visible release notes are packaged in the APK."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"

text = MAIN.read_text(encoding="utf-8")
anchor = '"v$NAME build$CODE · NAT 诊断、Agent 更新与映射界面修复" to listOf('
if anchor not in text:
    raise SystemExit("build165 release-note anchor missing")

notes = [
    "NAT 下拉菜单改为大圆角白色浮层，参数标题与内容拉开间距",
    "NAT 任务完成后耗时和路由器响应停止累计，保留最终结果",
    "Agent 更新检查改为 Hub 后台任务，502 不再显示原始 HTML",
    "SSH 小卡片改为浅灰色，今日概览同步状态移到右上角",
    "首页卡片拖动响应加快，点击反馈按卡片圆角裁剪",
]
missing = [note for note in notes if note not in text]
if missing:
    insertion = anchor + "\n" + "\n".join(f'                "{note}",' for note in missing)
    text = text.replace(anchor, insertion, 1)

MAIN.write_text(text, encoding="utf-8")
for note in notes:
    if note not in text:
        raise SystemExit(f"release note missing after patch: {note}")
print("build165 APK release notes guaranteed")
