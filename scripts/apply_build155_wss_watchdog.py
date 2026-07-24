#!/usr/bin/env python3
"""Relax APP-Hub WSS frame watchdog for mobile/proxy scheduling jitter."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WSS = ROOT / "app/src/main/kotlin/com/labprobe/app/HubMqttClient.kt"


def apply() -> None:
    text = WSS.read_text(encoding="utf-8")
    text = text.replace("const val PING_INTERVAL_SECONDS = 8L", "const val PING_INTERVAL_SECONDS = 10L")
    text = text.replace("const val SERVER_FRAME_TIMEOUT_MS = 8_000L", "const val SERVER_FRAME_TIMEOUT_MS = 20_000L")
    required = (
        "const val PING_INTERVAL_SECONDS = 10L",
        "const val SERVER_FRAME_TIMEOUT_MS = 20_000L",
        "const val WATCHDOG_INTERVAL_MS = 1_000L",
        "else -> 3_000L",
    )
    missing = [value for value in required if value not in text]
    if missing:
        raise RuntimeError(f"build155 WSS watchdog verification failed: {missing}")
    if "SERVER_FRAME_TIMEOUT_MS = 8_000L" in text:
        raise RuntimeError("aggressive 8-second WSS watchdog remains")
    WSS.write_text(text, encoding="utf-8")
    print("build155 APP-Hub WSS watchdog relaxed to 20 seconds")


if __name__ == "__main__":
    apply()
