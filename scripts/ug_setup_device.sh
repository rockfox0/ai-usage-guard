#!/usr/bin/env bash
# AI 使用管家 · 真机部署脚本
# 用法: ./scripts/ug_setup_device.sh [设备序列号]
# 作用: 安装 debug APK -> 授予「使用情况访问」-> 开启无障碍服务 -> 预置 AI 配置
set -euo pipefail

DEV="${1:-}"
ADB="$HOME/Android/Sdk/platform-tools/adb"
APK="$HOME/some_AI/android_AI/AI_d/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.aid.usageguard"
SVC="$PKG/$PKG.service.GuardService"

if [ -z "$DEV" ]; then
  DEV="$($ADB devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator/ {print $1; exit}')"
fi
[ -n "$DEV" ] || { echo "未找到已连接的真机"; exit 1; }
echo "== 设备: $DEV"

echo "== 安装 $APK"
$ADB -s "$DEV" install -r -t "$APK" | tail -1

# 重装后系统会重置无障碍授权，所以每次安装后都要重新开
echo "== 授予使用情况访问"
$ADB -s "$DEV" shell appops set "$PKG" GET_USAGE_STATS allow

echo "== 开启无障碍服务"
$ADB -s "$DEV" shell settings put secure enabled_accessibility_services "$SVC"
$ADB -s "$DEV" shell settings put secure accessibility_enabled 1
sleep 2

echo "== 校验"
$ADB -s "$DEV" shell appops get "$PKG" GET_USAGE_STATS
$ADB -s "$DEV" shell dumpsys accessibility 2>/dev/null | grep -m1 "Bound services"

# 可选：本地预置 AI 配置（BaseURL / API Key / 模型），避免手工输入
# 用环境变量传入: UG_BASE_URL / UG_API_KEY / UG_MODEL
if [ -n "${UG_API_KEY:-}" ]; then
  echo "== 预置 AI 配置（DataStore protobuf）"
  $ADB -s "$DEV" shell am force-stop "$PKG"
  python3 - "$UG_API_KEY" "${UG_BASE_URL:-https://api.deepseek.com}" "${UG_MODEL:-deepseek-chat}" "${UG_FORMAT:-openai}" <<'PYPREFS'
import sys
key, base, model, fmt = sys.argv[1:5]

def varint(n):
    out = b""
    while True:
        b = n & 0x7f
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out

def ld(num, data):
    return varint((num << 3) | 2) + varint(len(data)) + data

def strval(s):
    return ld(5, s.encode())

def intval(v):
    return varint((3 << 3) | 0) + varint(v)

def entry(k, v):
    return ld(1, k.encode()) + ld(2, v)

pairs = [
    ("ai_base_url", strval(base)),
    ("ai_api_key", strval(key)),
    ("ai_model", strval(model)),
    ("ai_format", strval(fmt)),
    ("daily_target_minutes", intval(120)),
]
open("/tmp/ug_datastore.pb", "wb").write(b"".join(ld(1, entry(k, v)) for k, v in pairs))
PYPREFS
  $ADB -s "$DEV" push /tmp/ug_datastore.pb /data/local/tmp/ug_datastore.pb >/dev/null
  $ADB -s "$DEV" shell "run-as $PKG sh -c 'cp /data/local/tmp/ug_datastore.pb files/datastore/app_settings.preferences_pb && chmod 600 files/datastore/app_settings.preferences_pb'"
  echo "   已写入 DataStore"
fi

echo "== 通知权限"
$ADB -s "$DEV" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo "== 启动应用"
$ADB -s "$DEV" shell am start -n "$PKG/.MainActivity" >/dev/null
echo "完成"
