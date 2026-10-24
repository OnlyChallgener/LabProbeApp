"""Capture the AppBao display actually containing the selected Android app.

The default screencap display is a 320x480 launcher surface, not the app.
Inputs are explicit, reviewed coordinates; this helper never changes settings.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = Path("D:/Tools/labprobe-ui/android-sdk/platform-tools/adb.exe")
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "test/_analysis/oct08-shinya-hub/ui-audit"


def adb(*args: str) -> str:
    value = subprocess.run([str(ADB), "-s", "127.0.0.1:5555", *args],
                           capture_output=True, timeout=25, check=True)
    return value.stdout.decode("utf-8", "replace")


def capture(name: str, physical: str, package: str) -> dict:
    OUT.mkdir(parents=True, exist_ok=True)
    remote = "/sdcard/shinya-audit"
    # Always retain the actual display image, even if Accessibility has no root.
    adb("shell", "screencap", "-p", "-d", physical, remote + ".png")
    adb("pull", remote + ".png", str(OUT / (name + ".png")))
    for attempt in range(3):
        output = adb("shell", "uiautomator", "dump", remote + ".xml")
        if "dumped to" in output:
            break
        if attempt == 0 and package == "cn.com.ruijie.shinya2":
            adb("shell", "am", "start", "--display", "5", "-n", package + "/.MainActivity")
        time.sleep(0.5)
    else:
        raise RuntimeError("Android returned no accessible root after three attempts")
    adb("pull", remote + ".xml", str(OUT / (name + ".xml")))
    # Capture again after a possible focus recovery so screenshot and XML match.
    adb("shell", "screencap", "-p", "-d", physical, remote + ".png")
    adb("pull", remote + ".png", str(OUT / (name + ".png")))
    root = ET.parse(OUT / (name + ".xml"))
    for node in root.iter("node"):
        if node.get("password") == "true":node.set("text", "[密码已隐藏]")
    root.write(OUT / (name + ".xml"),encoding="utf-8",xml_declaration=True)
    nodes = [n.attrib for n in root.iter("node")]
    observed = sorted({n.get("package", "") for n in nodes if n.get("package")})
    if package not in observed:
        raise RuntimeError("UI hierarchy belongs to a different app: " + str(observed))
    texts = [{"text": n["text"], "bounds": n.get("bounds", "")}
             for n in nodes if n.get("text")]
    row = {"name": name, "package": package, "physicalDisplay": physical,
           "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "texts": texts,
           "screenshot": name + ".png", "xml": name + ".xml",
           "sha256": hashlib.sha256((OUT / (name + ".png")).read_bytes()).hexdigest()}
    (OUT / (name + ".json")).write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding="utf-8")
    return row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("name")
    parser.add_argument("--display", default="5")
    parser.add_argument("--physical", default="4619827540095559171")
    parser.add_argument("--package", default="cn.com.ruijie.shinya2")
    parser.add_argument("--auto-display", action="store_true")
    parser.add_argument("--tap", nargs=2)
    parser.add_argument("--swipe", nargs=4)
    parser.add_argument("--back", action="store_true")
    args = parser.parse_args()
    if args.auto_display:
        inventory = adb("shell", "dumpsys", "display")
        ids = set(re.findall(r'mDisplayId=(\d+)', inventory)) - {'0'}
        devices = set(re.findall(r'uniqueId="local:(\d+)"', inventory)) - {'4619827259835644672'}
        if len(ids) != 1 or len(devices) != 1:
            raise RuntimeError('Expected exactly one visible AppBao app display')
        args.display, args.physical = ids.pop(), devices.pop()
    if args.tap:
        adb("shell", "input", "-d", args.display, "tap", *args.tap)
    if args.swipe:
        adb("shell", "input", "-d", args.display, "swipe", *args.swipe, "400")
    if args.back:
        adb("shell", "input", "-d", args.display, "keyevent", "4")
    if args.tap or args.back or args.swipe:
        time.sleep(1)
    row = capture(args.name, args.physical, args.package)
    visible = [n for n in row["texts"] if n["bounds"] != "[0,0][0,0]"]
    print(json.dumps({"name": row["name"], "texts": visible[:32],
                      "totalTexts": len(row["texts"])}, ensure_ascii=True))


if __name__ == "__main__":
    main()
