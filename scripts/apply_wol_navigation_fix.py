#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "app/src/main/kotlin/com/labprobe/app/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing WOL patch pattern: {label}")
    return text.replace(old, new, 1)


def apply() -> None:
    text = PATH.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '    var dailyReturnRoute by remember { mutableStateOf("events") }\n',
        '    var dailyReturnRoute by remember { mutableStateOf("events") }\n    var wolReturnRoute by remember { mutableStateOf("home") }\n',
        "return state",
    )
    text = replace_once(
        text,
        '            route == "wol" -> "devices"\n',
        '            route == "wol" -> wolReturnRoute.takeIf { it in mainRoutes } ?: "home"\n',
        "normalized route",
    )
    text = replace_once(
        text,
        '            if (target == "daily") dailyReturnRoute = if (route in mainRoutes) route else normalized\n',
        '            if (target == "daily") dailyReturnRoute = if (route in mainRoutes) route else normalized\n            if (target == "wol") wolReturnRoute = if (route in mainRoutes) route else normalized\n',
        "navigation capture",
    )
    text = replace_once(
        text,
        '                "wol" -> "home"\n',
        '                "wol" -> wolReturnRoute\n',
        "system back",
    )
    text = replace_once(
        text,
        '                        "wol" -> WolDetailScreen(state) { route = "home" }\n',
        '                        "wol" -> WolDetailScreen(state) { route = wolReturnRoute }\n',
        "screen back",
    )
    PATH.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    apply()
    print("WOL return-route fix applied")
