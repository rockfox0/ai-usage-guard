#!/usr/bin/env python3
"""AI 使用管家真机 UI 自动化小工具（adb + uiautomator dump）。

用法:
  python3 scripts/ug_ui.py dump                 # 打印可点击/有文字的节点
  python3 scripts/ug_ui.py tap "设置"            # 点击文本精确匹配的节点
  python3 scripts/ug_ui.py tap-contains "API Key"
  python3 scripts/ug_ui.py input "hello"        # 向已聚焦输入框输入文本
  python3 scripts/ug_ui.py swipe-up             # 上滑
  python3 scripts/ug_ui.py shot out/shots/x.png # 截图
"""
import html
import os
import re
import subprocess
import sys

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
SERIAL = os.environ.get("UG_SERIAL", "")


def sh(cmd: str) -> str:
    return subprocess.run([ADB, "-s", SERIAL, "shell", cmd],
                          capture_output=True, text=True).stdout


def dump_xml() -> str:
    sh("uiautomator dump /sdcard/ug_ui.xml >/dev/null 2>&1")
    return sh("cat /sdcard/ug_ui.xml")


def nodes(xml: str):
    out = []
    for tag in re.findall(r'<node [^>]*?/?>', xml):
        tm = re.search(r'\btext="([^"]*)"', tag)
        cm = re.search(r'\bclass="([^"]*)"', tag)
        bm = re.search(r'\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bm:
            continue
        l, t, r, b = map(int, bm.groups())
        out.append({
            "text": html.unescape(tm.group(1)) if tm else "",
            "class": cm.group(1) if cm else "",
            "center": ((l + r) // 2, (t + b) // 2),
            "bounds": (l, t, r, b),
        })
    return out


def find(xml, needle, contains=False):
    for n in nodes(xml):
        if not n["text"]:
            continue
        if (needle in n["text"]) if contains else (n["text"] == needle):
            return n
    return None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    cmd = sys.argv[1]
    xml = dump_xml()
    if cmd == "dump":
        for n in nodes(xml):
            if n["text"] or n["class"].endswith("EditText"):
                print(f'{n["text"]!r:50} {n["class"]:35} {n["center"]}')
        return 0
    if cmd in ("tap", "tap-contains"):
        needle = sys.argv[2]
        node = find(xml, needle, contains=(cmd == "tap-contains"))
        if not node:
            print(f"未找到: {needle}")
            return 2
        x, y = node["center"]
        sh(f"input tap {x} {y}")
        print(f"tap {needle} -> {x},{y}")
        return 0
    if cmd == "tap-xy":
        sh(f"input tap {sys.argv[2]} {sys.argv[3]}")
        return 0
    if cmd == "input":
        text = sys.argv[2].replace(" ", "%s")
        sh(f'input text "{text}"')
        print("input ok")
        return 0
    if cmd == "swipe-up":
        sh("input swipe 540 1900 540 1000 400")
        return 0
    if cmd == "shot":
        path = sys.argv[2]
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        with open(path, "wb") as f:
            subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                           stdout=f)
        print(f"shot -> {path}")
        return 0
    print(f"未知命令 {cmd}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
