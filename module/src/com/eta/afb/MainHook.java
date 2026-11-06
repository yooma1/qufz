package com.eta.afb;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.HookFailedError;

import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 反诈上报阻断（AntiFraudBlock / libxposed API 102 版）
 *
 * 作用域：com.coloros.phonemanager（含 :remote / :scan / :card 等子进程）
 *
 * 实测链路（PhoneManager 17.1.6 -> 17.5.4 / ColorOS 16，类名未混淆）：
 *   1. com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics
 *        反诈埋点封装：主开关点击、事件计数、推送触达、误报反馈、VOIP 白名单外应用
 *   2. com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils
 *        17.1.6：m(int,String,boolean,String,String) / n(Context)           混淆名
 *        17.5.4：uploadDetectInfo / uploadDetectInfo$default / uploadSwitches  原名
 *        云端 DCS 上报：号码、包名、通话时长、检测时长、判定码 —— 最敏感的一跳
 *   3. com.oplus.nearx.track.TrackApi
 *        17.1.6：E(String appId, String event, Map params)                  混淆名
 *        17.5.4：track(...) 全部重载 + trackTimerStart / trackTimerEnd       原名
 *        appId="20092"，全部手机管家遥测的最终出口
 *
 * 保留不动（本地功能必须照常）：
 *   RiskWarningUtilsKt.insertFraudDetectSecurityEvent      -> 本地反诈记录落库
 *   FraudDetectNotificationHelperKt.showRiskNotification   -> 本地风险通知
 *   FraudRiskDialogActivity / FraudDetectNotificationHelperKt -> 本地弹窗
 *   AiVoiceDetectForegroundService / SecurityEventStatisticsVM -> 本地检测服务与记录页
 */
public class MainHook extends XposedModule {

    private static final String TAG = "AntiFraudBlock";
    private static final String PKG = "com.coloros.phonemanager";
    private static final String TRACK_APP_ID = "20092";

    /** 逃生阀：存在该文件则本次注入全部空转（改完 kill 掉手机管家即恢复默认行为） */
    private static final String KILL_SWITCH = "/data/local/tmp/afb.disable";

    private static boolean sDisabled = false;

    private static final String[] BLOCK_PREFIX = {"anti_fraud", "ai_detect", "fraud"};

    private static final Set<String> BLOCK_EXACT = new HashSet<>();

    static {
        BLOCK_EXACT.add("anti_fraud_switches");
        BLOCK_EXACT.add("anti_fraud_events_count");
        BLOCK_EXACT.add("anti_fraud_master_switche");
        BLOCK_EXACT.add("anti_fraud_handle_event");
        BLOCK_EXACT.add("anti_fraud_false_report_event");
        BLOCK_EXACT.add("ai_detect_result_event");
        BLOCK_EXACT.add("ai_detect_voip_app_outside_lists");
    }

    /* ---------------------------------------------------------------- *
     * 方法名表：新旧版本都要覆盖。
     * 17.1.6 是混淆名，17.5.4 恢复了原名；只写一套必然在某版本上 0 命中。
     * ---------------------------------------------------------------- */

    /** DCS 云端上报（带号码那一跳）：17.5.4 原名 + 17.1.6 混淆名 */
    private static final Set<String> DCS_UPLOAD = new HashSet<>();

    /** DCS 本地记录/状态方法：这些必须保留，否则本地检测链路会坏 */
    private static final Set<String> DCS_LOCAL_KEEP = new HashSet<>();

    static {
        DCS_UPLOAD.add("uploadDetectInfo");
        DCS_UPLOAD.add("uploadDetectInfo$default");
        DCS_UPLOAD.add("uploadSwitches");
        DCS_UPLOAD.add("m");
        DCS_UPLOAD.add("n");

        // 这些是本地能力（写 SP / 内存 map / 修开关），拦了会影响本地功能
        DCS_LOCAL_KEEP.add("recordDetectTime");
        DCS_LOCAL_KEEP.add("recordFraudDetectCode");
        DCS_LOCAL_KEEP.add("recordFraudDetectStart");
        DCS_LOCAL_KEEP.add("recordFraudDetectTime");
        DCS_LOCAL_KEEP.add("recordStartTime");
        DCS_LOCAL_KEEP.add("recordVoiceDetectCode");
        DCS_LOCAL_KEEP.add("recordVoiceDetectStart");
        DCS_LOCAL_KEEP.add("getDetectStartTime");
        DCS_LOCAL_KEEP.add("getDetectTag");
        DCS_LOCAL_KEEP.add("hasEnteredAiFraudPage");
        DCS_LOCAL_KEEP.add("isFixFlagSet");
        DCS_LOCAL_KEEP.add("setFixFlag");
        DCS_LOCAL_KEEP.add("fixMasterSwitchIfNeeded");
        DCS_LOCAL_KEEP.add("clearSp");
        DCS_LOCAL_KEEP.add("release");
        DCS_LOCAL_KEEP.add("readConfigVersion");
    }

    /** TrackApi 出口方法：17.5.4 的 track 系列 + 17.1.6 的 E */
    private static final Set<String> TRACK_SINK_EXACT = new HashSet<>();

    static {
        TRACK_SINK_EXACT.add("E");
        TRACK_SINK_EXACT.add("track");
        TRACK_SINK_EXACT.add("track$default");
        // 注意：trackTimerStart / trackTimerEnd 只做本地计时，不是上报出口，
        // 拦了会破坏性能统计，故不纳入。
    }

    public MainHook() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("module loaded, api=" + getApiVersion() + " framework=" + getFrameworkName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (pkg == null || !pkg.startsWith(PKG)) return;

        sDisabled = new File(KILL_SWITCH).exists();
        log("attach " + pkg + (sDisabled ? " (kill switch ON, no-op)" : ""));

        ClassLoader cl = param.getDefaultClassLoader();

        // 1) 反诈埋点封装：所有 static void 全短路，但跳过 <clinit> 与 $default 桥接
        hookStaticVoid(cl,
                "com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics", null, true);

        // 2) DCS 云端上报：按名字表精确打击，跳过本地记录类方法
        hookStaticVoid(cl,
                "com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils", DCS_UPLOAD, false);

        // 3) 最终出口兜底：TrackApi 全部出口重载，按 appId + 事件名前缀拦截
        hookTrackSink(cl);

        log("kept local: insertFraudDetectSecurityEvent / showRiskNotification / FraudRiskDialogActivity");
    }

    /**
     * @param names    null = 该类所有 static void 都打
     * @param skipCtor true = 跳过 clinit 与 Kotlin 的 $default 桥接
     */
    @SuppressWarnings("unused")
    private void hookStaticVoid(ClassLoader cl, String clsName, final Set<String> names, boolean skipCtor) {
        try {
            Class<?> c = Class.forName(clsName, false, cl);
            int hit = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                String name = m.getName();
                if (skipCtor && ("<clinit>".equals(name) || name.endsWith("$default"))) continue;
                if (names != null && !names.contains(name)) continue;
                if (DCS_LOCAL_KEEP.contains(name)) continue;
                hookMethod(m, "silence " + clsName + "#" + name + "/" + m.getParameterTypes().length, true);
                hit++;
            }
            if (hit == 0) {
                // 0 命中说明版本又不匹配了，这层等于没装 —— 必须显眼
                warn(clsName + " -> 0 method(s) silenced (TARGET NOT FOUND, this layer is INACTIVE)");
            } else {
                log(clsName + " -> " + hit + " method(s) silenced");
            }
        } catch (Throwable t) {
            warn("hook class failed: " + clsName + " : " + t);
        }
    }

    @SuppressWarnings("unused")
    private void hookTrackSink(ClassLoader cl) {
        final String cls = "com.oplus.nearx.track.TrackApi";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            int hit = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!TRACK_SINK_EXACT.contains(m.getName())) continue;
                // appId / event 的位置取决于是否 static：
                //   实例方法 track(String appId, String event, ...)   -> 0,1
                //   静态桥接 track$default(TrackApi, String, String, ...) -> 1,2
                final boolean isStatic = Modifier.isStatic(m.getModifiers());
                final int appIdIdx = isStatic ? 1 : 0;
                final int eventIdx = isStatic ? 2 : 1;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length <= eventIdx) continue;
                if (ps[appIdIdx] != String.class || ps[eventIdx] != String.class) continue;

                hookMethod(m, "sink TrackApi." + m.getName() + "/" + ps.length, null,
                        appIdIdx, eventIdx);
                hit++;
            }
            if (hit == 0) {
                warn("TrackApi -> 0 sink hooked (TARGET NOT FOUND, fallback layer is INACTIVE)");
            } else {
                log("TrackApi -> " + hit + " sink hooked");
            }
        } catch (Throwable t) {
            warn("hook TrackApi failed: " + t);
        }
    }

    /**
     * @param always   true=无条件吞掉返回值；null=按事件名判定（TrackApi 出口用）；false=不动
     * @param appIdIdx 仅 always==null 时有效：appId 参数下标
     * @param eventIdx 仅 always==null 时有效：event 参数下标
     */
    private void hookMethod(Executable exe, final String label, final Boolean always,
                            final int appIdIdx, final int eventIdx) {
        try {
            hook(exe).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (sDisabled) return chain.proceed();
                    if (always == null) {
                        // TrackApi 出口的判定分支
                        Object appId = chain.getArg(appIdIdx);
                        Object ev = chain.getArg(eventIdx);
                        if (ev instanceof String
                                && TRACK_APP_ID.equals(String.valueOf(appId))
                                && isBlockedEvent((String) ev)) {
                            log("blocked track event: " + ev);
                            return null;
                        }
                        return chain.proceed();
                    }
                    if (always) {
                        log("hit " + label);
                        return null;
                    }
                    return chain.proceed();
                }
            });
        } catch (HookFailedError e) {
            warn("hook failed " + label + " : " + e);
        } catch (Throwable t) {
            warn("hook error " + label + " : " + t);
        }
    }

    private void hookMethod(Executable exe, String label, Boolean always) {
        hookMethod(exe, label, always, 0, 1);
    }

    private static boolean isBlockedEvent(String ev) {
        if (ev == null || ev.isEmpty()) return false;
        if (BLOCK_EXACT.contains(ev)) return true;
        String low = ev.toLowerCase(Locale.ROOT);
        for (String p : BLOCK_PREFIX) {
            if (low.startsWith(p)) return true;
        }
        return false;
    }

    private void log(String s) {
        try {
            Log.i(TAG, s);
        } catch (Throwable ignore) {
        }
        try {
            this.log(4, TAG, s);
        } catch (Throwable ignore) {
        }
    }

    /** 0 命中 / hook 失败走 WARN，避免混在 INFO 里被忽略 */
    private void warn(String s) {
        try {
            Log.w(TAG, s);
        } catch (Throwable ignore) {
        }
        try {
            this.log(5, TAG, s);
        } catch (Throwable ignore) {
        }
    }
}