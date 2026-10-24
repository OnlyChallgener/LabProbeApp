"""Visit reviewed, non-mutating official tool entries and record each page."""
import json
import re
import time
import xml.etree.ElementTree as ET

from adb_feature_audit import OUT, adb, capture

PACKAGE = "cn.com.ruijie.shinya2"
PHYSICAL = "4619827540095559171"


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node["bounds"]))
    if not (0 <= x1 < x2 <= 1001 and 0 <= y1 < y2 <= 1778):
        raise RuntimeError("Target is not visible")
    return str((x1 + x2) // 2), str((y1 + y2) // 2)


def tap(node):
    adb("shell", "input", "-d", "5", "tap", *center(node))
    time.sleep(0.8)


def nodes(name):
    return [n.attrib for n in ET.parse(OUT / (name + ".xml")).iter("node")]


def run_cases(cases):
    results = []
    for name, label in cases:
        parent = capture(name + "-parent", PHYSICAL, PACKAGE)
        if not any(n["text"] == "工具" for n in parent["texts"]):
            current = nodes(name + "-parent")
            backs = [n for n in current if n.get("content-desc") == "转到上一层级"
                     or n.get("text") == "\ueae7"]
            if not backs:
                raise RuntimeError("No observed back button before " + name)
            tap(backs[0])
            parent = capture(name + "-parent", PHYSICAL, PACKAGE)
        if not any(n["text"] == "工具" for n in parent["texts"]):
            raise RuntimeError("Navigation did not return to tools before " + name)
        targets = [n for n in parent["texts"] if n["text"] == label
                   and n["bounds"] != "[0,0][0,0]"]
        if not targets:
            raise RuntimeError("Entry is not visible: " + label)
        tap(targets[0])
        time.sleep(1.2)
        page = capture(name, PHYSICAL, PACKAGE)
        visible = [n["text"] for n in page["texts"] if n["bounds"] != "[0,0][0,0]"]
        changed = not any(n == "工具" for n in visible)
        row = {"case": name, "entry": label, "navigationChanged": changed,
               "scope": "read page only; saving changes was not exercised",
               "texts": visible[:28], "screenshot": name + ".png"}
        results.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
        (OUT / "official-tool-page-results.json").write_text(
            json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        if not changed:
            raise RuntimeError("Entry did not open: " + label)


if __name__ == "__main__":
    run_cases([
        ("official-blacklist", "蹭网拉黑"),
        ("official-schedule-reboot", "定时重启"),
        ("official-admin-password", "管理密码"),
        ("official-lan-setting", "内网设置"),
        ("official-router-upgrade", "路由升级"),
        ("official-child-management", "儿童上网"),
    ])
