#!/usr/bin/env bash
# Agent Benchmark（Case 1–8）：跑真实生产代码的决策链，不需要设备/API Key。
# 用法：scripts/agent_bench.sh   （在 AI_d 目录或任意位置执行）
set -euo pipefail
cd "$(dirname "$0")/.."
GRADLE_BIN="${GRADLE_BIN:-$HOME/.gradle/wrapper/dists/gradle-8.13-bin/ap7pdhvhnjtc6mxtzz89gkh0c/gradle-8.13/bin/gradle}"
"$GRADLE_BIN" :app:testDebugUnitTest --tests "*AgentBenchmarkTest*" --no-daemon --console=plain
echo "---- 从测试报告提取结果 ----"
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
for f in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*AgentBenchmarkTest.xml'):
    r = ET.parse(f).getroot()
    print((r.findtext('system-out') or '').strip())
PY
