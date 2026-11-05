#!/usr/bin/env python3
"""在 AI 助手页发送一条消息并等待回复（真机，无 root）。

用法: python3 scripts/ug_chat.py <serial> "<message>" [wait_seconds]

流程：切到 AI 助手 → 点输入框 → 输入 → 点「发送」→ 等待 → 打印最新会话。
"""
import html
import re
import subprocess
import sys
import time

ADB = "adb"
PKG = "com.aid.usageguard"


def adb(serial, *args, capture=False):
    cmd = [ADB, "-s", serial] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.stdout if capture else None


def dump(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/u.xml")
    return adb(serial, "shell", "cat", "/sdcard/u.xml", capture=True)


def nodes(xml):
    out = []
    for tag in re.findall(r"<node [^>]*?/?>", xml):
        bounds = re.search(r'\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bounds:
            continue
        cls = (re.search(r'\bclass="([^"]*)"', tag) or [None, ""])[1]
        text = re.search(r'\btext="([^"]*)"', tag)
        desc = re.search(r'\bcontent-desc="([^"]*)"', tag)
        l, t, r, b = map(int, bounds.groups())
        out.append({
            "cls": cls.split(".")[-1],
            "text": html.unescape(text.group(1)) if text else "",
            "desc": html.unescape(desc.group(1)) if desc else "",
            "center": ((l + r) // 2, (t + b) // 2),
        })
    return out


def tap(serial, xy):
    adb(serial, "shell", "input", "tap", str(xy[0]), str(xy[1]))


def open_assistant(serial):
    adb(serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3)
    for n in nodes(dump(serial)):
        if n["text"] == "AI 助手" and n["center"][1] > 2000:
            tap(serial, n["center"])
            return True
    return False


def main():
    serial, message = sys.argv[1], sys.argv[2]
    wait = int(sys.argv[3]) if len(sys.argv) > 3 else 45

    open_assistant(serial)
    time.sleep(2)

    edit = next((n for n in nodes(dump(serial)) if n["cls"] == "EditText"), None)
    if edit is None:
        print("找不到输入框", file=sys.stderr)
        return 1
    tap(serial, edit["center"])
    time.sleep(1.5)

    adb(serial, "shell", "input", "text", message.replace(" ", "%s"))
    time.sleep(1.5)

    send = next((n for n in nodes(dump(serial)) if n["desc"] == "发送"), None)
    if send is None:
        print("找不到发送按钮", file=sys.stderr)
        return 2
    tap(serial, send["center"])
    print(f"sent: {message!r}, waiting {wait}s ...")
    time.sleep(wait)

    for n in reversed(nodes(dump(serial))):
        if n["cls"] == "TextView" and len(n["text"]) > 20:
            print("---- latest text on screen ----")
            print(n["text"][:400])
            break
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
