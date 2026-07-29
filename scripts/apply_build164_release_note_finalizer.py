#!/usr/bin/env python3
"""Finalize user-visible build164 release notes after all generated-source patches."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


def apply() -> None:
    text = MAIN.read_text(encoding="utf-8")
    replacements = {
        "恢复 NAT 协议与 WAN 类型白色大圆角下拉框和科技蓝阴影": "终端实时栏的速率与连接数改为固定间距",
        "NAT 检测改为后台执行，检测过程、结果、超时与错误提示全部中文化": "路由 NAT 诊断参数框统一高度、圆角、字号与垂直居中",
    }
    for old, new in replacements.items():
        if new not in text:
            if old not in text:
                raise RuntimeError(f"missing build164 release-note anchor: {old}")
            text = text.replace(old, new, 1)
    MAIN.write_text(text, encoding="utf-8")
    for value in replacements.values():
        if value not in text:
            raise RuntimeError(f"missing finalized build164 release note: {value}")
    print("build164 release notes finalized")


if __name__ == "__main__":
    apply()
