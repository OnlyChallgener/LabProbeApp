"""Launch an installed build and prove the UI still responds to taps.

Run with a device attached. It taps every clickable node on each of the first
few screens and reports whether the hierarchy changed, plus the JS-side log
lines that would explain a frozen-but-drawing screen.
"""
import argparse
import hashlib
import re
import subprocess
import time
from pathlib import Path

ADB = Path("D:/tools/labprobe-ui/android-sdk/platform-tools/adb.exe")
NODE = re.compile(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>')


def adb(*args, timeout=60):
    return subprocess.run([str(ADB), *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout).stdout


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def targets(xml):
    found = []
    for text, x1, y1, x2, y2 in NODE.findall(xml):
        x1, y1, x2, y2 = map(int, (x1, y1, x2, y2))
        if x2 - x1 >= 40 and y2 - y1 >= 40:
            found.append((text or "(no text)", (x1 + x2) // 2, (y1 + y2) // 2))
    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk")
    parser.add_argument("--package", default="com.labprobe.shinyahub")
    parser.add_argument("--per-screen", type=int, default=4)
    parser.add_argument("--screens", type=int, default=3)
    args = parser.parse_args()

    if len([d for d in adb("devices").splitlines() if "\tdevice" in d]) != 1:
        raise SystemExit("need exactly one attached device:\n" + adb("devices"))
    if args.apk:
        print(adb("install", "-r", args.apk, timeout=600).strip())
    adb("logcat", "-c")
    activity = adb("shell", "cmd", "package", "resolve-activity", "--brief", args.package).strip().splitlines()[-1]
    adb("shell", "am", "start", "-W", "-n", activity)
    time.sleep(8)
    for screen in range(args.screens):
        before = hierarchy()
        print(f"screen {screen}: {len(before)} bytes, {len(targets(before))} targets")
        changed = 0
        for text, x, y in targets(before)[:args.per_screen]:
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(2)
            after = hierarchy()
            same = hashlib.sha1(after.encode()).digest() == hashlib.sha1(before.encode()).digest()
            print(f"  tap {text!r} at ({x},{y}): {'NO CHANGE' if same else 'changed'}")
            changed += not same
            before = after
        if not changed and targets(before):
            print("  every tap was inert: JS thread looks dead")
        adb("shell", "input", "keyevent", "4")
        time.sleep(2)
    logs = adb("logcat", "-d", "-v", "brief")
    hits = [line for line in logs.splitlines()
            if re.search(r"ReactNativeJS|Hermes|AndroidRuntime|FATAL|TypeError|Exception", line)]
    print("--- js/android log")
    print("\n".join(hits[-40:]) or "(no error lines)")


if __name__ == "__main__":
    main()
