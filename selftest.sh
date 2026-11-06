#!/bin/sh
# 自测：确认拦截确实生效，且本地反诈功能没被误伤
#
# 三段检查：
#   A. 装配 —— 模块是否注入、三处 hook 是否挂上（按"是否 0 命中"判定，不写死数字）
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

say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
ok()  { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
warn(){ printf '  \033[33mWARN\033[0m %s\n' "$1"; }
info(){ printf '       %s\n' "$1"; }
FAILED=0

# 框架探测：Vector 优先（zygisk_vector/cli），其次 LSPosed 2.2.0+（lspctl）。
# 两套都没有就只跳过装配段，不判 FAIL。
VECTOR_CLI=/data/adb/modules/zygisk_vector/cli
LSPCTL=/data/adb/modules/zygisk_lsposed/lspctl

say "A. 装配检查"

if [ -f "$VECTOR_CLI" ]; then
  info "框架：Vector"
  if sh "$VECTOR_CLI" modules ls 2>/dev/null | grep -q 'com.eta.afb.*enabled'; then
    ok "模块已启用"
  else
    bad "模块未启用（Vector 管理器 → 反诈上报阻断 → 启用）"
  fi
  if sh "$VECTOR_CLI" scope ls com.eta.afb 2>/dev/null | grep -q "$PKG"; then
    ok "作用域含 $PKG"
  else
    bad "作用域缺 $PKG"
  fi
elif [ -f "$LSPCTL" ]; then
  info "框架：LSPosed"
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
else
  warn "未找到 Vector cli 或 lspctl，跳过启用/作用域检查"
  info "（LSPosed 2.2.0 以下没有 lspctl；Vector 才有 cli）"
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

# 框架会把模块日志镜像一份，先去掉重复行，避免命中数翻倍
UNIQ=$(echo "$LOG" | sed 's/^.*AntiFraudBlock: //' | grep -v '^com\.eta\.afb: ' | sort -u)

echo "$UNIQ" | grep -q 'module loaded' && ok "module loaded" || bad "未见 module loaded（模块没注入）"

# 三处 hook：只要不是 0 命中就算挂上；0 命中说明该版本方法名对不上，这层等于没装
check_hook() {
  label="$1"; pat="$2"
  line=$(echo "$UNIQ" | grep -- "$pat" | head -1)
  if [ -z "$line" ]; then
    bad "$label 无日志（hook 未执行）"
    return
  fi
  n=$(echo "$line" | sed -n 's/.*-> \([0-9][0-9]*\) .*/\1/p')
  if [ -z "$n" ]; then
    echo "$line" | grep -q 'TARGET NOT FOUND' && { bad "$label 目标不存在（该层 INACTIVE）"; return; }
    ok "$label"; return
  fi
  if [ "$n" = "0" ]; then
    bad "$label 0 命中（该层 INACTIVE，方法名可能与本机版本不匹配）"
  else
    ok "$label（$n 个）"
  fi
}

check_hook "AiDetectStatistics 埋点层" "AiDetectStatistics ->"
check_hook "AiVoiceDetectDCSUtils DCS 层" "AiVoiceDetectDCSUtils ->"
check_hook "TrackApi 兜底层" "TrackApi ->"

echo "$UNIQ" | grep -q 'hook class failed' && bad "有类 hook 失败（版本不匹配？看下方原文）" && echo "$UNIQ" | grep 'hook class failed' | sed 's/^/       /'
echo "$UNIQ" | grep -q 'TARGET NOT FOUND' && warn "有层报告 TARGET NOT FOUND，见上"

if [ "${1:-}" = "--active" ]; then
  say "B. 主动触发（打开反诈页）"
  am start -a "$ACTION_HOME" -n "$ACT" >/dev/null 2>&1
  sleep 8
  HITS=$(logcat -d 2>/dev/null | grep 'AntiFraudBlock.*hit silence' | grep -v 'Framework' | wc -l)
  if [ "$HITS" -gt 0 ]; then
    ok "命中 $HITS 次拦截"
    logcat -d 2>/dev/null | grep 'AntiFraudBlock.*hit silence' | grep -v 'Framework' | tail -5 | sed 's/^/       /'
  else
    info "本轮没命中（反诈页没产生埋点也算正常，可手动切一下反诈页的开关再跑）"
  fi
  BLOCKED=$(logcat -d 2>/dev/null | grep 'AntiFraudBlock.*blocked track event' | grep -v 'Framework' | wc -l)
  info "sink 层吞掉的 TrackApi 事件：$BLOCKED 条"
  if [ "$BLOCKED" = "0" ]; then
    info "（为 0 说明反诈埋点在 AiDetectStatistics 层就被挡在源头，没走到 TrackApi，属正常）"
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
  printf '  提示：0 命中通常意味着手机管家升级后方法名变了，\n'
  printf '        需要重新反编译对照 aivoicecalldetect.utils.* 与 TrackApi 的方法表。\n'
  exit 1
fi