#!/usr/bin/env python3
"""Final branch-head CI mapping for BE72 radio temperatures and storage."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LITE = ROOT / "app/src/main/kotlin/com/labprobe/app/LiteRealtime.kt"
MAIN = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing build156 patch pattern: {label}")
    return text.replace(old, new, 1)


def apply() -> None:
    text = LITE.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '    telemetry.put("temperatureC", sample.optDouble("temperatureC", telemetry.optDouble("temperatureC", 0.0)))\n',
        '''    telemetry.put("temperatureC", sample.optDouble("temperatureC", telemetry.optDouble("temperatureC", 0.0)))
    if (sample.has("temperature2gC") && !sample.isNull("temperature2gC")) {
        telemetry.put("temperature2gC", sample.optDouble("temperature2gC", telemetry.optDouble("temperature2gC", 0.0)))
    }
    if (sample.has("temperature5gC") && !sample.isNull("temperature5gC")) {
        telemetry.put("temperature5gC", sample.optDouble("temperature5gC", telemetry.optDouble("temperature5gC", 0.0)))
    }
    if (sample.has("storagePercent") && !sample.isNull("storagePercent")) {
        telemetry.put("storagePercent", sample.optDouble("storagePercent", telemetry.optDouble("storagePercent", 0.0)))
    }
''',
        "radio temperature and storage mapping",
    )
    required = (
        'sample.has("temperature2gC")',
        'telemetry.put("temperature2gC"',
        'sample.has("temperature5gC")',
        'telemetry.put("temperature5gC"',
        'sample.has("storagePercent")',
        'telemetry.put("storagePercent"',
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"build156 LiteRealtime verification failed: {missing}")
    LITE.write_text(text, encoding="utf-8")

    main = MAIN.read_text(encoding="utf-8")
    old = '''            "v$NAME build$CODE · 长连接启动与路由功能恢复" to listOf(
                "APP 启动优先建立 Hub 原生 WSS，完整 HTTP 同步不再阻塞连接",
                "放宽移动网络帧看门狗并保留最后有效数据，避免健康长连接被误判断线",
                "终端在线卡片按真实在线数和 Hub 状态显示，不再把零关注误写为等待同步",
                "恢复路由设置、映射与 UPnP、防火墙、DDNS、自检、NAT 与 Beta 的真实页面入口"
            )'''
    new = '''            "v$NAME build$CODE · 路由字段与长连接完整修复" to listOf(
                "APP 启动优先建立 Hub 原生 WSS，完整 HTTP 同步不再阻塞连接",
                "长连接保活期间保留最后有效数据，短暂网络抖动不再反复显示重连",
                "终端在线卡片按真实在线数和 Hub 状态显示，不再错误停留在等待同步",
                "恢复真实路由功能页，并补齐 2.4G、5G 温度和存储占用字段"
            )'''
    main = replace_once(main, old, new, "build156 changelog")
    MAIN.write_text(main, encoding="utf-8")
    print("build156 router temperature, storage and changelog mapping applied")


if __name__ == "__main__":
    apply()
