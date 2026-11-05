#!/usr/bin/env python3
"""读取设备上 usageguard 的 DataStore 设置（无 root，走 run-as）。"""
import subprocess
import sys

SERIAL = sys.argv[1] if len(sys.argv) > 1 else None
PKG = "com.aid.usageguard"
PATH = "files/datastore/app_settings.preferences_pb"


def read_bytes():
    cmd = [str(subprocess.os.environ.get("ADB", "adb"))]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += ["exec-out", "run-as", PKG, "cat", PATH]
    out = subprocess.run(cmd, capture_output=True)
    return out.stdout


def varint(b, i):
    shift = 0
    val = 0
    while True:
        c = b[i]
        i += 1
        val |= (c & 0x7F) << shift
        if not c & 0x80:
            return val, i
        shift += 7


def parse_msg(b):
    """返回 [(field_no, wire_type, value)]，value 为 int/bytes。"""
    out = []
    i = 0
    while i < len(b):
        tag, i = varint(b, i)
        field, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = varint(b, i)
            out.append((field, wt, v))
        elif wt == 2:
            ln, i = varint(b, i)
            out.append((field, wt, b[i:i + ln]))
            i += ln
        elif wt == 5:
            out.append((field, wt, b[i:i + 4]))
            i += 4
        elif wt == 1:
            out.append((field, wt, b[i:i + 8]))
            i += 8
        else:
            raise ValueError(f"unsupported wire type {wt}")
    return out


def decode_value(msg):
    fields = parse_msg(msg)
    for f, wt, v in fields:
        if f == 1 and wt == 0:
            return bool(v)
        if f == 3 and wt == 0:
            return v
        if f == 4 and wt == 0:
            return v
        if f == 5 and wt == 2:
            return v.decode("utf-8", "replace")
        if f == 6 and wt == 2:
            strings = [s.decode("utf-8", "replace") for f2, wt2, s in parse_msg(v) if f2 == 1 and wt2 == 2]
            return sorted(strings)
    return f"<raw {fields}>"


def main():
    data = read_bytes()
    if not data:
        print("读取失败（应用未安装或 run-as 不可用）", file=sys.stderr)
        return 1
    entries = parse_msg(data)
    for f, wt, v in entries:
        if f != 1 or wt != 2:
            continue
        kv = parse_msg(v)
        key = None
        value = None
        for kf, kwt, kvv in kv:
            if kf == 1 and kwt == 2:
                key = kvv.decode("utf-8", "replace")
            elif kf == 2 and kwt == 2:
                value = decode_value(kvv)
        print(f"{key:28} = {value!r}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
