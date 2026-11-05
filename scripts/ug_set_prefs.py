#!/usr/bin/env python3
"""在设备上直接读写 usageguard 的 DataStore 设置（无需 root，debug 包 run-as）。

用法:
  python3 scripts/ug_set_prefs.py <serial> list
  python3 scripts/ug_set_prefs.py <serial> set ai_base_url=http://host:port ai_api_key=sk-xxx
  python3 scripts/ug_set_prefs.py <serial> del ai_api_key
支持类型推断：true/false → bool；整数 → int；逗号分隔且以 [] 包裹 → string set（如 allowed=[a,b]）
"""
import subprocess
import sys

ADB = "/home/f0x/Android/Sdk/platform-tools/adb"
PKG = "com.aid.usageguard"
PB = "files/datastore/app_settings.preferences_pb"


def sh(serial, *args, capture=False):
    r = subprocess.run([ADB, "-s", serial] + list(args), capture_output=True)
    return r.stdout if capture else None


def enc_varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def read_varint(b, i):
    shift = val = 0
    while True:
        c = b[i]
        i += 1
        val |= (c & 0x7F) << shift
        if not c & 0x80:
            return val, i
        shift += 7


def parse(b):
    out, i = [], 0
    while i < len(b):
        tag, i = read_varint(b, i)
        f, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = read_varint(b, i)
            out.append((f, wt, v))
        elif wt == 2:
            ln, i = read_varint(b, i)
            out.append((f, wt, b[i:i + ln]))
            i += ln
        elif wt == 5:
            out.append((f, wt, b[i:i + 4]))
            i += 4
        elif wt == 1:
            out.append((f, wt, b[i:i + 8]))
            i += 8
        else:
            raise ValueError(f"wire type {wt}")
    return out


def decode_value(v):
    for f, wt, val in parse(v):
        if f == 1 and wt == 0:
            return ("bool", bool(val))
        if f == 3 and wt == 0:
            return ("int", val)
        if f == 4 and wt == 0:
            return ("long", val)
        if f == 5 and wt == 2:
            return ("str", val.decode("utf-8", "replace"))
        if f == 6 and wt == 2:
            return ("set", sorted(s.decode("utf-8", "replace")
                                 for f2, w2, s in parse(val) if f2 == 1 and w2 == 2))
    return None


def encode_value(value):
    kind, val = value
    if kind == "bool":
        return enc_varint((1 << 3) | 0) + enc_varint(1 if val else 0)
    if kind in ("int", "long"):
        return enc_varint((3 << 3) | 0) + enc_varint(val)
    if kind == "str":
        return ld(5, val.encode())
    if kind == "set":
        return ld(6, b"".join(ld(1, v.encode()) for v in sorted(val)))
    raise ValueError(kind)


def ld(num, data):
    return enc_varint((num << 3) | 2) + enc_varint(len(data)) + data


def read_pairs(serial):
    raw = sh(serial, "exec-out", "run-as", PKG, "cat", PB, capture=True)
    if not raw:
        raise SystemExit("读取 DataStore 失败（应用未安装或 run-as 不可用）")
    pairs = {}
    for f, wt, v in parse(raw):
        if f != 1 or wt != 2:
            continue
        key = value = None
        for kf, kwt, kvv in parse(v):
            if kf == 1 and kwt == 2:
                key = kvv.decode()
            elif kf == 2 and kwt == 2:
                value = decode_value(kvv)
        if key:
            pairs[key] = value
    return pairs


def write_pairs(serial, pairs):
    data = b"".join(ld(1, ld(1, k.encode()) + ld(2, encode_value(v)))
                    for k, v in pairs.items() if v)
    with open("/tmp/ug_prefs_write.pb", "wb") as fh:
        fh.write(data)
    sh(serial, "shell", "am", "force-stop", PKG)
    sh(serial, "push", "/tmp/ug_prefs_write.pb", "/data/local/tmp/ug_prefs_write.pb")
    sh(serial, "shell",
       f"run-as {PKG} sh -c 'cp /data/local/tmp/ug_prefs_write.pb {PB} && chmod 600 {PB}'")
    # 重启无障碍服务
    sh(serial, "shell", "settings", "put", "secure", "enabled_accessibility_services", "")
    sh(serial, "shell", "settings", "put", "secure", "accessibility_enabled", "0")
    sh(serial, "shell", "settings", "put", "secure", "enabled_accessibility_services",
       f"{PKG}/{PKG}.service.GuardService")
    sh(serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1")


def infer(raw):
    if raw in ("true", "false"):
        return ("bool", raw == "true")
    if raw.startswith("[") and raw.endswith("]"):
        items = [x.strip() for x in raw[1:-1].split(",") if x.strip()]
        return ("set", sorted(items))
    try:
        return ("int", int(raw))
    except ValueError:
        return ("str", raw)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    serial, action = sys.argv[1], sys.argv[2]
    pairs = read_pairs(serial)
    if action == "list":
        for k, v in sorted(pairs.items()):
            shown = "<hidden>" if "key" in k.lower() else v[1]
            print(f"{k} = {shown!r}")
        return 0
    if action == "set":
        for item in sys.argv[3:]:
            key, _, raw = item.partition("=")
            pairs[key] = infer(raw)
        write_pairs(serial, pairs)
        print("updated:", [i.split("=")[0] for i in sys.argv[3:]])
        return 0
    if action == "del":
        for key in sys.argv[3:]:
            pairs.pop(key, None)
        write_pairs(serial, pairs)
        print("deleted:", sys.argv[3:])
        return 0
    print(f"未知动作 {action}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
