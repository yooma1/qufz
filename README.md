# AntiFraudBlock · 反诈上报阻断

一个只做一件事的 LSPosed 模块：**掐断手机管家 AI 反诈的遥测与云端上报，本地风险提醒照常工作。**

作用域只有 `com.coloros.phonemanager`（ColorOS / OPPO 手机管家），不碰系统框架、不碰其他应用。

```text
com.coloros.phonemanager 17.1.6 · ColorOS 16 · Android 16 · RMX5200
```

## 本工作副本：RMX5080 / 手机管家 17.3.10 适配

版本调整为 **v2.1（versionCode 3）**，在保留 17.1.6 旧 Hook 的同时加入：

- `AiVoiceDetectDCSUtils.uploadDetectInfo`
- `AiVoiceDetectDCSUtils.uploadDetectInfo$default`
- `AiVoiceDetectDCSUtils.uploadSwitches`
- `TrackApi.track` 的全部重载（按 `20092` 与反诈事件名过滤）

17.3.10 的新增目标已从 RMX5080 实际提取的 APK 中静态核实；当前适配版尚未在 LSPosed 环境中上机验证。开发构建产物为 `out/AntiFraudBlock-2.1.apk`。

---

## 为什么单独做模块，不并进 LuckyTool

| 理由 | 具体 |
|---|---|
| 作用域不同 | LuckyTool 的 scope 表全是 ColorOS 包，没有 `com.miui.*`；它的页面是 Compose 硬编码页（`ScopePageRegistry` 里 51 页固定排序），每加一页都要动注册表 + 功能树 + 字符串 |
| 上游会变 | LuckyTool 一更新，本地改动就要重新对齐 `ScopePageSpec` / `HookAppFeatureProvider`，冲突成本高 |
| 功能边界清晰 | 本模块只有"阻断上报"一条链路，出问题能整包关掉，不影响 LuckyTool 的其他增强 |

HyperCeiler / Cemiuiler / MIUI_NoGuard 那套（`com.miui.guardprovider`、`com.miui.analytics.onetrack.*`、`trustservice` 的 `mrmd`）是纯小米命名空间，在 ColorOS 上**一个 class 都不存在**，没法照搬。

---

## 实测链路（不是推测，全部从本机 APK 反出）

APK：`/product/priv-app/PhoneManager/PhoneManager.apk`，v17.1.6，5 个 dex，**类名未混淆**。

| 层 | 真实类 / 方法 | 干什么 | 本模块 |
|---|---|---|---|
| 反诈埋点封装 | `aivoicecalldetect.utils.AiDetectStatistics`<br>`b/c/d/e/g/h/i/j`（static void） | 主开关点击、事件计数、推送触达、误报反馈、VOIP 白名单外应用 | **拦截** |
| 云端 DCS 上报 | `aivoicecalldetect.utils.AiVoiceDetectDCSUtils`<br>`m(int,String,boolean,String,String)` / `n(Context)` | 上传号码、包名、通话时长、检测时长、判定码 | **拦截** |
| 事件最终出口 | `com.oplus.nearx.track.TrackApi.E(String appId, String event, Map)`<br>appId = `"20092"` | 所有手机管家遥测的公共出口 | **按事件名拦截** |
| 本地记录落库 | `RiskWarningUtilsKt.insertFraudDetectSecurityEvent` | 反诈记录页的数据来源 | 保留 |
| 本地风险通知 | `FraudDetectNotificationHelperKt.showRiskNotification` | 通知栏诈骗提醒 | 保留 |
| 本地风险弹窗 | `FraudRiskDialogActivity` | 通话中/事后弹窗 | 保留 |
| 本地检测服务 | `AiVoiceDetectForegroundService` | 通话反诈检测本身 | 保留 |
| 本地记录页 | `SecurityEventStatisticsVM` / `SecurityEventStatisticsActivity` | 读本地 DB，不走 TrackApi | 保留 |

被拦的事件名（实测存在于 `AiDetectStatistics` 各 suspend lambda 里的 `vf.l.r(ctx,"20092",<event>,map)`）：

```text
anti_fraud_switches              anti_fraud_events_count
anti_fraud_master_switche        anti_fraud_handle_event
anti_fraud_false_report_event    ai_detect_result_event
ai_detect_voip_app_outside_lists
```

外加前缀兜底：`anti_fraud*` / `ai_detect*` / `fraud*`，用于接住后续版本新增的事件名。

### 三层为什么都拦

- 只拦 `AiDetectStatistics` → 漏掉 `SimCallDetectManager` / `VoipCallDetectTriggerReceiver` 直接调 `AiVoiceDetectDCSUtils.m(...)` 的那条（这条带**手机号**，最敏感）。
- 只拦 `AiVoiceDetectDCSUtils` → 漏掉 `ExaminationStatisticsUtilsKt` 直接调 `.n(context)` 与 `TrackApi.E` 的场景。
- 三层都拦 + sink 兜底 → 新版加了新埋点入口也能被事件名前缀接住。

---

## 本地功能为什么不受影响

`SecurityEventStatisticsVM` 的数据来自本地 DB（`insertFraudDetectSecurityEvent` → `records.e`），`FraudDetectNotificationHelperKt` 直接发通知，`FraudRiskDialogActivity` 直接起 Activity —— 这三条都**不经过** `TrackApi.E`，也不看 `AiDetectStatistics` 的返回值。

所以：**手机照常弹诈骗提醒、照常在记录页留历史，只是不再往云端和统计平台上报。**

---

## 安装

1. 安装 `release/AntiFraudBlock-2.0.apk`
2. LSPosed 里启用，作用域勾 **手机管家（com.coloros.phonemanager）**
3. 强停手机管家后重新打开（`am force-stop com.coloros.phonemanager`）

命令行装 + 验证：

```bash
adb install -r AntiFraudBlock-2.0.apk

# LSPosed 2.2.0 起有 lspctl，可直接核对状态
/data/adb/modules/zygisk_lsposed/lspctl module show com.eta.afb
#   API: min=101 target=102 / Type: modern / Static scope: yes / User 0: enabled
/data/adb/modules/zygisk_lsposed/lspctl scope list com.eta.afb
#   0  com.coloros.phonemanager

# 看是否真的挂上
logcat -s AntiFraudBlock:*
```

正常输出：

```text
I AntiFraudBlock: module loaded, api=102 framework=LSPosed
I AntiFraudBlock: attach com.coloros.phonemanager
I AntiFraudBlock: com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics -> 9 method(s) silenced
I AntiFraudBlock: com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils -> 2 method(s) silenced
I AntiFraudBlock: TrackApi.E -> 1 sink hooked
I AntiFraudBlock: kept local: insertFraudDetectSecurityEvent / showRiskNotification / FraudRiskDialogActivity
I AntiFraudBlock: hit silence ...AiDetectStatistics#h/3      ← 真实命中
```

### 逃生阀

```bash
touch /data/local/tmp/afb.disable     # 本次注入全部空转
am force-stop com.coloros.phonemanager
```

删掉文件再强停一次即恢复阻断。用来快速做 A/B 对照。

---

## 自测：确认拦截真的生效

```bash
./selftest.sh            # 只检查装配与本地功能（不动屏幕）
./selftest.sh --active   # 额外打开反诈页产生真实事件
```

脚本在设备上 root shell 里跑（`adb shell sh /path/selftest.sh`），做三段检查，全过退出码 0：

**A. 装配** —— `lspctl` 查模块启用状态与作用域；强停手机管家后冷启动，轮询 logcat 最多 15 秒，核对三处 hook 是否都报告就位。

**B. 拦截** —— 打开 `SecurityHomeActivity`，统计 `hit silence` 行数。实测样例：

```text
OK   命中 1 次拦截
     11-04 14:16:54.390  7216  7216 I AntiFraudBlock: hit silence com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics#h/3
     sink 层吞掉的 TrackApi 事件：0 条
     （为 0 说明反诈埋点在 AiDetectStatistics 层就被挡在源头，根本没走到 TrackApi.E，属正常）
```

`sink 层 = 0` 是**预期**而不是故障：源头层已经短路，`TrackApi.E` 兜底层没机会触发。它只在手机管家换了新埋点入口、而事件名仍命中前缀时才会计数。

**C. 本地功能** —— 反诈页在任务栈存活、crash 缓冲区无手机管家记录、本地反诈配置文件仍在。

### 两个真实踩过的坑（已在脚本里修掉）

| 坑 | 症状 | 修法 |
|---|---|---|
| LSPosed 把模块日志镜像成 `LSPosedFramework` 行 | `grep -c` 把命中数算成 2 倍 | 计数前加 `grep -v LSPosedFramework` |
| 冷启动 + 日志落盘有延迟，固定 `sleep 6` 不够 | 偶发 **A 段全 FAIL**，但 B 段能看到 `hit silence`（自相矛盾） | 改成轮询到出现 `kept local` 或超时 15 秒 |

---

## 构建

```bash
./build.sh          # -> out/AntiFraudBlock-2.1.apk
```

需要 `javac(11+)`、`aapt2`、`d8`、`zipalign`、`apksigner`、`android.jar(platform 34)`。
`libxposed api 102` 首次构建自动从 Maven Central 拉（约 30KB）。

### 构建模式与签名（重要）

```bash
./build.sh                  # 开发构建 → out/（不进 git）
./build.sh release          # 发布构建 → release/（进 git，随仓库分发）
```

`release/` 里的包用**正式密钥**签名，密钥不入库。发布构建必须显式指定：

```bash
AFB_KS=~/afb-release.jks AFB_KS_PASS=xxx AFB_KS_ALIAS=afb AFB_KS_KEYPASS=xxx ./build.sh release
```

没指定 `AFB_KS` 就跑 `release` 时，脚本会**拒绝执行**并在 stderr 提示（防止 debug 证书覆盖正式包）；确实要用 debug 证书覆盖，加 `AFB_KS_ALLOW_DEBUG=1`。

| | 证书 DN | 证书 SHA-256 |
|---|---|---|
| `release/` 正式包 | `CN=AntiFraudBlock, O=ETA, C=CN` | `46d15cd9…32d5c1` |
| `./build.sh` 默认 | `C=US, O=Android, CN=Android Debug` | `b0d023e8…36faa2` |

两者**签名不同，不能互相覆盖安装**。装过 `release/` 包的机器装重建版要先 `adb uninstall com.eta.afb`，重装后在 LSPosed 里重新勾作用域。

目录：

```text
module/                       libxposed modern 模块源（当前版本）
  src/com/eta/afb/MainHook.java
  AndroidManifest.xml
  META-INF/xposed/{module.prop,scope.list,java_init.list}
  res/values/strings.xml
legacy/v1.0-xposed82/         最初试的 legacy Xposed API 82 版本（被 LSPosed 判为
                              非 modern 模块、默认 disabled，已废弃，仅留档）
release/                      已签名发布产物（入库）
out/                          开发构建产物（.gitignore）
build.sh                      构建脚本
push.sh                       推送脚本（qufz 部署密钥）
selftest.sh                   上机自测（装配 / 拦截 / 本地功能三段核对）
docs/REVERSING.md             逆向取证记录
```

---

## 版本

- **v2.0**（当前）libxposed API 102 / `XposedModule` modern 形态。LSPosed 自动识别、`xposedscope` 由 `META-INF/xposed/scope.list` 静态声明。
- **v1.0**（废弃）legacy `IXposedHookLoadPackage` + `api-82.jar`。装上去 LSPosed 认成 legacy 模块，`User 0: disabled`，需手动启用且 hook 不生效。留作对照，别用。

---

## 已知边界

- 只在 RMX5200 / ColorOS 16 / 手机管家 17.1.6 实测。ColorOS 版本差异会导致类名变动 —— 类名变了就去 `logcat -s AntiFraudBlock:*` 看 `hook class failed`，用 jadx 重新对一遍 `aivoicecalldetect.utils.*`。
- 拦的是**手机管家自身**的上报。第三方 App 通过其他通道上报的数据不在范围内。
- `TrackApi.E` 是通用的，本模块只吞 `appId == "20092"` 且事件名命中前缀的那些，其他 appId 的事件照常走。
- 手机管家后续版本如果改走 native 上报或换 SDK，需要重新逆向，本模块的 java 层 hook 会失效（但不会导致崩溃，只是拦不住）。

---

## 参考

- [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) — 小米系同类实现（`FuckMiuiUpload` / `DisableMrm` / `DisableReport`），思路参考，代码不通用
- [MIUI_NoGuard](https://github.com/gykkuo/MIUI_NoGuard) — 最早的 guardsprovider + analytics 拦截，纯 MIUI
- [libxposed API](https://github.com/libxposed/api) — 本模块用的 modern 模块接口

---

## License

MIT
