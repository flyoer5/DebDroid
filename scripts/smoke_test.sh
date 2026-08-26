#!/usr/bin/env bash
# v2 端到端冒烟测试（阶段五使用）。在 android-emulator-runner 内执行：
# 装 APK → 启动 → 点「安装 Debian」→ 等解压 → 验证 proot/bash 进程与终端渲染。
#
# 环境：$APK 指向构建产物；SMOKE_WAIT_SCALE 慢模拟器放大等待倍数。
set -u
SCALE="${SMOKE_WAIT_SCALE:-1}"
wait_for() { sleep $(( $1 * SCALE )); }
loop_n()  { seq 1 $(( $1 * SCALE )); }

trap 'adb logcat -d > smoke-logcat.txt 2>/dev/null || true' EXIT

find_and_tap() {
  adb exec-out uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml ui.xml >/dev/null
  python3 - "$1" <<'EOF'
import re, sys
text = sys.argv[1]
try:
    xml = open('ui.xml', encoding='utf-8').read()
except IOError:
    print('NOT_FOUND'); sys.exit(0)
m = re.search(r'text="([^"]*%s[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml)
if not m:
    print('NOT_FOUND'); sys.exit(0)
x=(int(m.group(2))+int(m.group(4)))//2; y=(int(m.group(3))+int(m.group(5)))//2
print('%d %d'%(x,y))
EOF
}

echo "=== Installing APK ==="
adb install -r "$APK"
adb logcat -c
adb shell am start -n com.debdroid.app/.MainActivity
# 权限对话框兜底：后台持续点 Allow
( for i in $(loop_n 240); do find_and_tap "Allow" || wait_for 2; done ) & ALLOW_PID=$!
wait_for 8

echo "=== Tapping Install Debian ==="
for i in 1 2 3 4 5; do
  COORDS=$(find_and_tap "安装 Debian")
  if [ -n "$COORDS" ] && [ "$COORDS" != "NOT_FOUND" ]; then
    adb shell input tap $COORDS; break
  fi
  COORDS=$(find_and_tap "Install Debian")
  if [ -n "$COORDS" ] && [ "$COORDS" != "NOT_FOUND" ]; then
    adb shell input tap $COORDS; break
  fi
  wait_for 4
done

echo "=== Waiting for extraction + session start ==="
ok=0
for i in $(loop_n 240); do
  if grep -qE 'Install failed|安装失败' ui.xml 2>/dev/null; then ok=2; break; fi
  if adb shell ps -A 2>/dev/null | grep -qw proot; then ok=1; break; fi
  wait_for 5
done

if [ "$ok" != "1" ]; then
  echo "=== INSTALL/SESSION FAILED ==="
  head -c 4000 ui.xml 2>/dev/null; echo
  adb logcat -d > smoke-logcat.txt
  grep -E "ProotLauncher|DebDroidSession|AndroidRuntime|FATAL|RootfsInstaller" smoke-logcat.txt | head -200
  exit 1
fi

echo "=== PROOT RUNNING ==="
adb shell ps -A | grep -wE "proot|bash|tmux" || true

echo "=== Type a command and verify output ==="
adb shell input text 'echo hello-debdroid'
adb shell input keyevent 66   # ENTER
wait_for 6
adb exec-out uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml ui.xml >/dev/null
if grep -q "hello-debdroid" ui.xml; then
  echo "=== ECHO VERIFIED ==="
else
  echo "=== ECHO NOT VISIBLE (dump tail) ==="
  grep -oE 'text="[^"]*"' ui.xml | tail -20
  exit 1
fi

echo "=== SMOKE TEST PASSED ==="
