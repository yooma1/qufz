#!/bin/sh
# 自测：确认拦截确实生效，且本地反诈功能没被误伤
#
# 三段检查：
#   A. 装配 —— 模块是否注入、三处 hook 是否挂上
#   B. 拦截 —— 反诈页/开关操作时是否命中，且没有反诈事件漏到 TrackApi
#   C. 本地功能 —— 反诈页正常打开、无崩溃、本地数据仍在
#
# 用法：
#   ./selftest.sh            # 只做 A/C（被动观察）
#   ./selftest.sh --active   # 额外打开反诈页产生真实事件（会短暂切前台）
set -u

ACTION_HOME=com.oplus.phonemanager.action.ANTI_FRAUD_HOME
ACT=com.coloros.phonemanager/com.oplus.phonemanager.aivoicecalldetect.antifraudhome.SecurityHomeActivity
FRAUD_PAGE=SecurityHomeActivity
PKG=com.coloros.phonemanager
LSPCTL=/data/adb/modules/zygisk_lsposed/lspctl

say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
ok()  { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
info(){ printf '       %s\n' "$1"; }
FAILED=0

say "A. 装配检查"
if "$LSPCTL" module show com.eta.afb 2>/dev/null | grep -q 'User 0: enabled'; then
  ok "模块已启用"
else
  bad "模块未启用（LSPosed → 反诈上报阻断 → 启用 + 勾手机管家）"
fi

if "$LSPCTL" scope list com.eta.afb 2>/dev/null | grep -q "$PKG"; then
  ok "作用域含 $PKG"
else
  bad "作用域缺 $PKG"
fi

am force-stop "$PKG" 2>/dev/null
sleep 1
logcat -c 2>/dev/null

# 顺手把逃生阀清掉，否则 hook 全空转
[ -f /data/local/tmp/afb.disable ] && { info "移除逃生阀 afb.disable"; rm -f /data/local/tmp/afb.disable; }

am start -n "$PKG/com.oplus.phonemanager.FakeActivity" >/dev/null 2>&1

# 进程冷启动 + 日志落盘都有延迟，轮询取并集，最多等 15 秒
LOG=""
i=0
while [ $i -lt 15 ]; do
  sleep 1
  LOG=$(logcat -d 2>/dev/null | grep 'AntiFraudBlock')
  if echo "$LOG" | grep -q 'kept local'; then break; fi
  i=$((i+1))
done

echo "$LOG" | grep -q 'module loaded' && ok "module loaded" || bad "未见 module loaded（模块没注入）"
echo "$LOG" | grep -Eq 'AiDetectStatistics -> [1-9][0-9]* method' \
  && ok "AiDetectStatistics 已静默（数量随手机管家版本变化）" || bad "AiDetectStatistics hook 缺失"
echo "$LOG" | grep -Eq 'AiVoiceDetectDCSUtils -> [1-9][0-9]* method' \
  && ok "AiVoiceDetectDCSUtils 已静默（含新旧上传方法）" || bad "DCS 上报 hook 缺失"
if echo "$LOG" | grep -Eq 'TrackApi\.E -> [1-9][0-9]* sink hooked' \
  || echo "$LOG" | grep -Eq 'TrackApi\.track -> [1-9][0-9]* sink hooked'; then
  ok "TrackApi 出口已挂（E 或 track）"
else
  bad "TrackApi 出口未挂"
fi
echo "$LOG" | grep -q 'hook class failed' && bad "有类 hook 失败（版本不匹配？看下方原文）" && echo "$LOG" | grep 'hook class failed' | info
[ "$FAILED" = "0" ] && info "全部 3 处 hook 就位"

if [ "${1:-}" = "--active" ]; then
  say "B. 主动触发（打开反诈页）"
  am start -a "$ACTION_HOME" -n "$ACT" >/dev/null 2>&1
  sleep 8
  # LSPosed 会把模块日志镜像成一条 LSPosedFramework 行，必须刨掉，否则命中数翻倍
  HITS=$(logcat -d 2>/dev/null | grep 'AntiFraudBlock.*hit silence' | grep -v 'LSPosedFramework' | wc -l)
  if [ "$HITS" -gt 0 ]; then
    ok "命中 $HITS 次拦截"
    logcat -d 2>/dev/null | grep 'AntiFraudBlock.*hit silence' | grep -v 'LSPosedFramework' | tail -5 | sed 's/^/       /'
  else
    info "本轮没命中（反诈页没产生埋点也算正常，可手动切一下反诈页的开关再跑）"
  fi
  BLOCKED=$(logcat -d 2>/dev/null | grep 'AntiFraudBlock.*blocked track event' | grep -v 'LSPosedFramework' | wc -l)
  info "sink 层吞掉的 TrackApi 事件：$BLOCKED 条"
  if [ "$BLOCKED" = "0" ]; then
    info "（为 0 说明反诈埋点在 AiDetectStatistics/DCS 层就被挡住，未走到 TrackApi.E/track，属正常）"
  fi
fi

say "C. 本地功能未被误伤"
if dumpsys activity activities 2>/dev/null | grep -q "$FRAUD_PAGE"; then
  ok "反诈页 SecurityHomeActivity 正常存活"
else
  info "反诈页未在任务栈（未打开过，或已退出）"
fi

if logcat -d -b crash 2>/dev/null | grep -qi "$PKG"; then
  bad "crash 缓冲区有手机管家记录"
  logcat -d -b crash 2>/dev/null | grep -i "$PKG" | head -5 | sed 's/^/       /'
else
  ok "crash 缓冲区无手机管家记录"
fi

for f in /data/oplus/os/phonemanager/fraud_detect_apps.xml /data/oplus/os/phonemanager/fraud_parameters.json; do
  [ -f "$f" ] && info "本地反诈配置在：$f" 
done

say "结论"
if [ "$FAILED" = "0" ]; then
  printf '  \033[32m拦截链完整，本地功能正常\033[0m\n'
  exit 0
else
  printf '  \033[31m有检查未通过，看上面 FAIL 行\033[0m\n'
  exit 1
fi
